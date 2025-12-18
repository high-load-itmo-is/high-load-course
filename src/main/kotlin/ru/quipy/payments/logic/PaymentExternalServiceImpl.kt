package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.asCoroutineDispatcher
import org.slf4j.LoggerFactory
import io.micrometer.core.instrument.MeterRegistry
import io.netty.channel.ChannelOption
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.http.HttpProtocol
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import ru.quipy.common.utils.CompositeRateLimiter
import ru.quipy.common.utils.RateLimiter
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.math.roundToLong


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
        
        val connectionProvider: ConnectionProvider = ConnectionProvider.builder("payment-provider")
            .maxConnections(100_000)
            .pendingAcquireMaxCount(100_000)
            .pendingAcquireTimeout(Duration.ofSeconds(120))
            .maxIdleTime(Duration.ofSeconds(60))
            .build()
        
        val sharedHttpClient: HttpClient = HttpClient.create(connectionProvider)
            .responseTimeout(Duration.ofMillis(120000))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val rateLimiter: RateLimiter = SlidingWindowRateLimiter(
        (rateLimitPerSec * 1.05).roundToLong(), // небольшой запас, чтобы компенсировать джиттер таймеров
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

    private val dispatcher = Executors
        .newFixedThreadPool((parallelRequests * 2).coerceAtLeast(128))
        .asCoroutineDispatcher()
    private val coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    private val webClient: WebClient = WebClient.builder()
        .baseUrl("http://$paymentProviderHostPort")
        .clientConnector(ReactorClientHttpConnector(sharedHttpClient))
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val transactionId = UUID.randomUUID()

        try {
            // Log submission synchronously to ensure aggregate exists and preserve ordering
            paymentESService.update(paymentId) {
                it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
            }
        } catch (e: Exception) {
            logger.error("[$accountName] Failed to log submission for payment $paymentId", e)
        }

        executePaymentReactive(paymentId, amount, transactionId)
    }

    private fun executePaymentReactive(paymentId: UUID, amount: Int, transactionId: UUID) {
        // First respect RPS limits to avoid acquiring parallel slot too early
        if (!rateLimiter.tick()) {
            coroutineScope.launch {
                delay(0)
                executePaymentReactive(paymentId, amount, transactionId)
            }
            return
        }
        // Then try to acquire a parallel requests slot
        if (!semaphore.tryAcquire()) {
            coroutineScope.launch {
                delay(0)
                executePaymentReactive(paymentId, amount, transactionId)
            }
            return
        }

        webClient.post()
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
            .doFinally { semaphore.release() }
            .subscribe(
                { responseBody ->
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

                    coroutineScope.launch {
                        try {
                            paymentESService.update(paymentId) {
                                it.logProcessing(body.result, now(), transactionId, reason = body.message)
                            }
                        } catch (e: Exception) {
                            logger.error("[$accountName] Failed to log processing result for payment $paymentId", e)
                        }
                    }
                },
                { error ->
                    logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", error)

                    when (error) {
                        is TimeoutException, is SocketTimeoutException -> timeoutCounter.increment()
                        else -> errorCounter.increment()
                    }

                    coroutineScope.launch {
                        try {
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, reason = error.message)
                            }
                        } catch (e: Exception) {
                            logger.error("[$accountName] Failed to log failure for payment $paymentId", e)
                        }
                    }
                }
            )
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName
}

data class PaymentResult(val success: Boolean, val message: String?)

public fun now() = System.currentTimeMillis()
