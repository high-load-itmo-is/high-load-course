package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.*
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.http.client.HttpClient
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        
        // Shared dispatcher for blocking DB operations - much larger than default Dispatchers.IO (64 threads)
        // This allows handling many concurrent blocking ES operations
        val blockingDispatcher = Executors.newFixedThreadPool(
            500,
            NamedThreadFactory("es-blocking")
        ).asCoroutineDispatcher()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val rateLimiter = SlidingWindowRateLimiter(
        rateLimitPerSec.toLong(),
        Duration.ofSeconds(1)
    )
    private val semaphore = Semaphore(parallelRequests)

    // Async WebClient with Netty - non-blocking HTTP client
    private val webClient: WebClient = WebClient.builder()
        .baseUrl("http://$paymentProviderHostPort")
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(Duration.ofMillis(120000))
            )
        )
        .build()

    // Coroutine scope for async operations - uses default dispatcher, blocking calls use blockingDispatcher
    private val paymentScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("payment-$accountName")
    )

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")

        val transactionId = UUID.randomUUID()

        // Launch async coroutine - truly non-blocking, returns IMMEDIATELY
        paymentScope.launch {
            try {
                // Move ALL blocking ES operations inside the coroutine with dedicated dispatcher
                withContext(blockingDispatcher) {
                    paymentESService.update(paymentId) {
                        it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
                    }
                }

                logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

                val result = executePaymentWithRetry(paymentId, amount, transactionId, maxAttempts = 3)

                withContext(blockingDispatcher) {
                    paymentESService.update(paymentId) {
                        it.logProcessing(result.success, now(), transactionId, reason = result.message)
                    }
                }
            } catch (e: Exception) {
                when (e) {
                    is TimeoutException, is SocketTimeoutException -> {
                        logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
                        withContext(blockingDispatcher) {
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
                            }
                        }

                        meterRegistry.counter(
                            "service_outgoing_requests_total",
                            "target", paymentProviderHostPort,
                            "account", accountName,
                            "status", "timeout"
                        ).increment()
                    }

                    else -> {
                        logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)

                        withContext(blockingDispatcher) {
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, reason = e.message)
                            }
                        }

                        meterRegistry.counter(
                            "service_outgoing_requests_total",
                            "target", paymentProviderHostPort,
                            "account", accountName,
                            "status", "exception"
                        ).increment()
                    }
                }
            }
        }
    }

    private suspend fun executePaymentWithRetry(
        paymentId: UUID,
        amount: Int,
        transactionId: UUID,
        maxAttempts: Int
    ): PaymentResult {
        var attempt = 0
        var lastResult: PaymentResult? = null

        while (attempt < maxAttempts) {
            attempt++
            logger.info("[$accountName] Attempt $attempt/$maxAttempts for payment $paymentId, txId: $transactionId")

            // Acquire semaphore permit - suspending, non-blocking
            semaphore.withPermit {
                // Wait for rate limiter - suspending version
                tickBlockingSuspend()

                try {
                    // Start measuring request time
                    val startTime = System.nanoTime()

                    // Async HTTP call using WebClient
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
                        .awaitSingle()

                    // Calculate request duration
                    val duration = System.nanoTime() - startTime

                    val body = try {
                        mapper.readValue(responseBody, ExternalSysResponse::class.java)
                    } catch (e: Exception) {
                        logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, reason: $responseBody")
                        ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                    }

                    logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}, attempt: $attempt")

                    // Record request latency
                    Timer.builder("payment_request_latency_seconds")
                        .description("Payment request latency")
                        .tags(
                            "target", paymentProviderHostPort,
                            "account", accountName,
                            "status_code", "200",
                            "result", body.result.toString()
                        )
                        .publishPercentileHistogram()
                        .register(meterRegistry)
                        .record(duration, TimeUnit.NANOSECONDS)

                    meterRegistry.counter(
                        "service_outgoing_requests_total",
                        "target", paymentProviderHostPort,
                        "account", accountName,
                        "status", "200"
                    ).increment()

                    lastResult = PaymentResult(body.result, body.message)

                    // If successful, return immediately and record retries
                    if (body.result) {
                        // Record number of retries (attempts - 1)
                        val retryCount = attempt - 1
                        if (retryCount > 0) {
                            recordRetries(retryCount, "success")
                        }
                        return lastResult!!
                    }

                    // If failed and we have more attempts, continue to retry
                    if (attempt < maxAttempts) {
                        logger.warn("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId. Retrying...")
                    }
                } catch (e: Exception) {
                    when {
                        e is TimeoutException || e.cause is TimeoutException -> {
                            if (attempt < maxAttempts) {
                                logger.warn("[$accountName] Payment request had timeout for txId: $transactionId, payment: $paymentId. Retrying...")
                            } else {
                                throw e
                            }
                        }
                        else -> {
                            throw e
                        }
                    }
                }
            }
        }

        // All attempts exhausted - record retries for failed payment
        val retryCount = attempt - 1
        if (retryCount > 0) {
            recordRetries(retryCount, "failed")
        }

        // Return the last result after all attempts
        return lastResult ?: PaymentResult(false, "All retry attempts failed")
    }

    // Suspending version of tickBlocking - doesn't block threads
    private suspend fun tickBlockingSuspend() {
        while (!rateLimiter.tick()) {
            delay(10)
        }
    }

    private fun recordRetries(retryCount: Int, outcome: String) {
        // Counter for total number of retries
        meterRegistry.counter(
            "payment_retries_total",
            "target", paymentProviderHostPort,
            "account", accountName,
            "outcome", outcome
        ).increment(retryCount.toDouble())

        // Counter for payments that needed retries
        meterRegistry.counter(
            "payment_requests_with_retries_total",
            "target", paymentProviderHostPort,
            "account", accountName,
            "outcome", outcome,
            "retry_count", retryCount.toString()
        ).increment()

        logger.info("[$accountName] Recorded $retryCount retries with outcome: $outcome")
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

data class PaymentResult(val success: Boolean, val message: String?)

public fun now() = System.currentTimeMillis()
