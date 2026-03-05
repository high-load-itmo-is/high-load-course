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
import kotlin.math.min
import kotlin.random.Random


class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val meterRegistry: MeterRegistry,
) : PaymentExternalSystemAdapter {
    private val max429Retries = 3
    private val maxRetryDelayMs = 1_000L
    private val initialRetryDelayMs = 100L

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()

        val connectionProvider: ConnectionProvider = ConnectionProvider.builder("payment-provider")
            .maxConnections(64)
            .pendingAcquireMaxCount(5_000)
            .pendingAcquireTimeout(Duration.ofMillis(200))
            .maxIdleTime(Duration.ofSeconds(60))
            .build()

        val sharedHttpClient: HttpClient = HttpClient.create(connectionProvider)
            .protocol(HttpProtocol.H2C)
            .responseTimeout(Duration.ofMillis(900))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 200)

        private val sharedDispatcher = Executors.newFixedThreadPool(
            128,
            NamedThreadFactory("payment-worker")
        ).asCoroutineDispatcher()
        private val dbDispatcher = Executors.newFixedThreadPool(
            128,
            NamedThreadFactory("payment-worker")
        ).asCoroutineDispatcher()

        val paymentScope = CoroutineScope(SupervisorJob() + sharedDispatcher)
        val dbScope = CoroutineScope(dbDispatcher)
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

    override suspend fun performPaymentAsync(orderId: UUID, paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long): Job {
        val transactionId = UUID.randomUUID()
        return paymentScope.launch {
            val created = withContext(dbDispatcher) {
                try {
                    paymentESService.create {
                        it.create(paymentId, orderId, amount)
                    }
                    OrderPayer.logger.trace("Payment $paymentId for order $orderId created.")
                    true
                } catch (e: Exception) {
                    OrderPayer.logger.error("Error creating payment $paymentId for order $orderId", e)
                    false
                }
            }
            if (!created) return@launch

            withContext(dbDispatcher) {
                try {
                    paymentESService.update(paymentId) {
                        it.logSubmission(
                            success = true,
                            transactionId,
                            now(),
                            Duration.ofMillis(now() - paymentStartedAt)
                        )
                    }
                } catch (e: Exception) {
                    logger.error("[$accountName] Failed to log submission for payment $paymentId", e)
                }
            }

            executePaymentReactive(paymentId, amount, transactionId, deadline)
        }
    }

    private suspend fun executePaymentReactive(paymentId: UUID, amount: Int, transactionId: UUID, deadline: Long) {
        var attempt = 1
        while (attempt <= max429Retries) {
            try {
                if (!rateLimiter.tick()) {
                    ++attempt
                    continue
                }
                semaphore.acquire()
                val responseBody = try {
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
                        .awaitSingle()
                } finally {
                    semaphore.release()
                }

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

                withContext(dbDispatcher) {
                    try {
                        paymentESService.update(paymentId) {
                            it.logProcessing(body.result, now(), transactionId, reason = body.message)
                        }
                    } catch (e: Exception) {
                        logger.error("[$accountName] Failed to log processing result for payment $paymentId", e)
                    }
                }
                return
            } catch (e: TooManyRequests) {
                if (attempt >= max429Retries) {
                    logger.error("[$accountName] Payment failed for txId: $transactionId with 429 after $attempt attempts")
                    errorCounter.increment()
                    logFailure(paymentId, transactionId, e.message ?: "Too Many Requests")
                    return
                }

                val delayMs = calculateRetryDelayMs(e, attempt, deadline)
                if (delayMs <= 0L) {
                    logger.error("[$accountName] Payment failed for txId: $transactionId due to deadline before retry")
                    errorCounter.increment()
                    logFailure(paymentId, transactionId, "Deadline reached before retry")
                    return
                }

                logger.warn("[$accountName] 429 for txId: $transactionId, retry in ${delayMs}ms, attempt=$attempt")
                delay(delayMs)
                attempt++
            } catch (e: Exception) {
                logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)

                when (e) {
                    is TimeoutException, is SocketTimeoutException -> timeoutCounter.increment()
                    else -> errorCounter.increment()
                }

                logFailure(paymentId, transactionId, e.message)
                return
            }
        }
    }

    private suspend fun logFailure(paymentId: UUID, transactionId: UUID, reason: String?) {
        withContext(dbDispatcher) {
            try {
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, reason = reason)
                }
            } catch (ex: Exception) {
                logger.error("[$accountName] Failed to log failure for payment $paymentId", ex)
            }
        }
    }

    private fun calculateRetryDelayMs(error: TooManyRequests, attempt: Int, deadline: Long): Long {
        val retryAfterSeconds = error.headers.getFirst("Retry-After")?.toLongOrNull()
        val retryAfterDelay = retryAfterSeconds?.times(1_000)
        val exponentialDelay = min(maxRetryDelayMs, initialRetryDelayMs * (1L shl (attempt - 1)))
        val jitter = Random.nextLong(0, 50)
        val selectedDelay = (retryAfterDelay ?: exponentialDelay) + jitter
        val remainingTime = deadline - now()
        return min(selectedDelay, remainingTime)
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName
}

data class PaymentResult(val success: Boolean, val message: String?)

public fun now() = System.currentTimeMillis()
