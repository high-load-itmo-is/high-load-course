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
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.http.HttpProtocol
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException


class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val meterRegistry: MeterRegistry,
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()

        private val sharedDispatcher = Executors.newFixedThreadPool(
            64,
            NamedThreadFactory("payment-worker")
        ).asCoroutineDispatcher()
        
        val paymentScope = CoroutineScope(SupervisorJob() + sharedDispatcher)

        val connectionProvider: ConnectionProvider = ConnectionProvider.builder("payment-provider")
            .maxConnections(100_000)
            .pendingAcquireMaxCount(100_000)
            .pendingAcquireTimeout(Duration.ofSeconds(120))
            .maxIdleTime(Duration.ofSeconds(60))
            .build()

        val sharedHttpClient: HttpClient = HttpClient.create(connectionProvider)
            .protocol(HttpProtocol.H2C)
            .responseTimeout(Duration.ofMillis(120000))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
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

    override suspend fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val transactionId = UUID.randomUUID()

        try {
            paymentESService.update(paymentId) {
                it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
            }
        } catch (e: Exception) {
            logger.error("[$accountName] Failed to log submission for payment $paymentId", e)
        }

        executePaymentReactive(paymentId, amount, transactionId)
    }

    private suspend fun executePaymentReactive(paymentId: UUID, amount: Int, transactionId: UUID) {
        semaphore.acquire()
        rateLimiter.tickBlocking()

        try {
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

            val body = try {
                mapper.readValue(responseBody, ExternalSysResponse::class.java)
            } catch (e: Exception) {
                logger.error("[$accountName] Failed to parse response for payment $paymentId: $responseBody")
                ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
            }

            if (logger.isDebugEnabled) {
                logger.debug("[$accountName] Payment processed for txId: $transactionId, succeeded: ${body.result}")
            }

            successCounter.increment()

            try {
                paymentESService.update(paymentId) {
                    it.logProcessing(body.result, now(), transactionId, reason = body.message)
                }
            } catch (e: Exception) {
                logger.error("[$accountName] Failed to log processing result for payment $paymentId", e)
            }

        } catch (e: Exception) {
            logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)

            when (e) {
                is TimeoutException, is SocketTimeoutException -> timeoutCounter.increment()
                else -> errorCounter.increment()
            }

            try {
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, reason = e.message)
                }
            } catch (ex: Exception) {
                logger.error("[$accountName] Failed to log failure for payment $paymentId", ex)
            }
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName
}

data class PaymentResult(val success: Boolean, val message: String?)

public fun now() = System.currentTimeMillis()
