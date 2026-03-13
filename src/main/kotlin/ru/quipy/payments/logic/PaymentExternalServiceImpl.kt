package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.*
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import io.micrometer.core.instrument.MeterRegistry
import io.netty.channel.ChannelOption
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException.TooManyRequests
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.http.HttpProtocol
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import reactor.netty.resources.LoopResources
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.logic.OrderPayer.Companion
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException


class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val meterRegistry: MeterRegistry,
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()

        val connectionProvider: ConnectionProvider = ConnectionProvider.builder("payment-provider")
            .maxConnections(16)
            .pendingAcquireTimeout(Duration.ofSeconds(120))
            .maxIdleTime(Duration.ofSeconds(60))
            .build()

        val httpLoopResources: LoopResources = LoopResources.create("payment-http-client", 64, true)

        val sharedHttpClient: HttpClient = HttpClient.create(connectionProvider)
            .runOn(httpLoopResources)
            .protocol(HttpProtocol.H2C)
            .responseTimeout(Duration.ofMillis(120000))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)

        private val sharedDispatcher = Executors.newFixedThreadPool(
            64,
            NamedThreadFactory("payment-worker")
        ).asCoroutineDispatcher()

        val paymentScope = CoroutineScope(SupervisorJob() + sharedDispatcher)
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val rateLimiter = SlidingWindowRateLimiter(
        rateLimitPerSec.toLong(),
        Duration.ofSeconds(1)
    )
    private val semaphore = Semaphore(parallelRequests)
    
    private val successCounter = meterRegistry.counter(
        "service_outgoing_requests_total", 
        "target", paymentProviderHostPort, 
        "account", accountName, 
        "status", "200"
    )
    private val timeoutCounter = meterRegistry.counter(
        "service_outgoing_requests_total",
        "target", paymentProviderHostPort,
        "account", accountName,
        "status", "timeout"
    )
    private val errorCounter = meterRegistry.counter(
        "service_outgoing_requests_total",
        "target", paymentProviderHostPort,
        "account", accountName,
        "status", "exception"
    )
    
    init {
        logger.warn("[$accountName] Initialized with rateLimitPerSec=$rateLimitPerSec, parallelRequests=$parallelRequests")
    }

    private val webClient: WebClient = WebClient.builder()
        .baseUrl("http://$paymentProviderHostPort")
        .clientConnector(ReactorClientHttpConnector(sharedHttpClient))
        .build()

    override fun performPaymentAsync(orderId: UUID, paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val transactionId = UUID.randomUUID()

        paymentScope.launch {
            executePaymentReactive(paymentId, amount, transactionId)
        }
    }

    private suspend fun executePaymentReactive(paymentId: UUID, amount: Int, transactionId: UUID) {
        try {
            semaphore.acquire()
            rateLimiter.tickSuspending()
            val responseBody = webClient.post()
                .uri { uriBuilder ->
                    uriBuilder.path("/external/process")
                        .queryParam("serviceName", serviceName)
                        .queryParam("token", token)
                        .queryParam("accountName", accountName)
                        .queryParam("transactionId", transactionId)
                        .queryParam("paymentId", paymentId)
                        .queryParam("amount", amount)
                        .build()
                }
                .retrieve()
                .bodyToMono<String>()
                .doFinally {
                    semaphore.release()
                }
                .awaitSingle()

            successCounter.increment()
        } catch (e: TooManyRequests) {
            logger.error("[$accountName] Payment failed for txId: $transactionId, with 429, retrying")
            executePaymentReactive(paymentId, amount, transactionId)
        } catch (e: Exception) {
            logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)

            when (e) {
                is TimeoutException, is SocketTimeoutException -> timeoutCounter.increment()
                else -> errorCounter.increment()
            }
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName
}

data class PaymentResult(val success: Boolean, val message: String?)

public fun now() = System.currentTimeMillis()
