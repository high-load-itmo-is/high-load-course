package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.web.server.ResponseStatusException
import ru.quipy.common.utils.TokenBucketRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val meterRegistry: MeterRegistry,
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)

        val emptyBody = RequestBody.create(null, ByteArray(0))
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val acquirePollIntervalMs = 5L
    private val ratePollIntervalMs = 5L
    private val minBudgetMsForAttempt = 200L
    private val safetyMarginMs = 50L

    private val rateLimiter = TokenBucketRateLimiter(
        rate = rateLimitPerSec,
        bucketMaxCapacity = rateLimitPerSec,
        window = 1,
        timeUnit = TimeUnit.SECONDS
    )
    private val semaphore = Semaphore(parallelRequests);

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")

        fun remaining(deadlineMs: Long) = deadlineMs - now()
        fun hasMinimalBudget(deadlineMs: Long) = remaining(deadlineMs) > minBudgetMsForAttempt

        fun markSubmitted(success: Boolean, tx: UUID) =
            paymentESService.update(paymentId) {
                it.logSubmission(success, tx, now(), Duration.ofMillis(now() - paymentStartedAt))
            }

        fun markProcessed(success: Boolean, tx: UUID, reason: String?) =
            paymentESService.update(paymentId) {
                it.logProcessing(success, now(), tx, reason)
            }

        fun incMetric(status: String) = meterRegistry.counter(
            "service_outgoing_requests_total",
            "target", paymentProviderHostPort,
            "account", accountName,
            "status", status
        ).increment()

        fun waitForSemaphoreOrTimeout(deadlineMs: Long, onTimeout: () -> Unit): Boolean {
            while (!semaphore.tryAcquire()) {
                if (!hasMinimalBudget(deadlineMs)) {
                    onTimeout()
                    return false
                }
                Thread.sleep(acquirePollIntervalMs)
            }
            return true
        }

        fun waitForRateTokenOrTimeout(deadlineMs: Long, onTimeout: () -> Unit): Boolean {
            while (!rateLimiter.tick()) {
                if (!hasMinimalBudget(deadlineMs)) {
                    onTimeout()
                    return false
                }
                Thread.sleep(ratePollIntervalMs)
            }
            return true
        }

        fun buildRequest(txId: UUID) = Request.Builder()
            .url("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$txId&paymentId=$paymentId&amount=$amount")
            .post(emptyBody)
            .build()

        fun buildCallClient(timeBudgetMs: Long) = client.newBuilder()
            .callTimeout(timeBudgetMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeBudgetMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeBudgetMs, TimeUnit.MILLISECONDS)
            .connectTimeout(minOf(timeBudgetMs, 1000L), TimeUnit.MILLISECONDS)
            .build()

        try {
            var attempt = 1
            while (true) {
                if (!hasMinimalBudget(deadline)) {
                    val tx = UUID.randomUUID()
                    markSubmitted(false, tx)
                    markProcessed(false, tx, "Insufficient time budget before call")
                    return
                }

                val txId = UUID.randomUUID()
                markSubmitted(true, txId)

                var acquired = false
                try {
                    if (!waitForSemaphoreOrTimeout(deadline) {
                            markProcessed(false, txId, "Insufficient time budget in queue")
                        }
                    ) return
                    acquired = true

                    if (!waitForRateTokenOrTimeout(deadline) {
                            markProcessed(false, txId, "Insufficient time budget due to rate limiting")
                        }
                    ) return

                    val timeBudgetMs = remaining(deadline) - safetyMarginMs
                    if (timeBudgetMs <= 0) {
                        markProcessed(false, txId, "No time budget after acquiring permit")
                        return
                    }

                    val effectiveTimeoutMs = properties.clientTimeoutMs?.let { minOf(timeBudgetMs, it) } ?: timeBudgetMs
                    val request = buildRequest(txId)
                    val callClient = buildCallClient(effectiveTimeoutMs)

                    var callStartNs = 0L
                    try {
                        callStartNs = System.nanoTime()
                        callClient.newCall(request).execute().use { response ->
                            val statusCode = response.code
                            val body = try {
                                mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
                            } catch (e: Exception) {
                                logger.error("[$accountName] [ERROR] Payment processed for txId: $txId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                                ExternalSysResponse(txId.toString(), paymentId.toString(), false, e.message)
                            }

                            logger.warn("[$accountName] Payment processed for txId: $txId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

                            val callDuration = System.nanoTime() - callStartNs
                            Timer
                                .builder("payment_provider_request_duration_seconds")
                                .tags(
                                    "target", paymentProviderHostPort,
                                    "account", accountName,
                                    "status", statusCode.toString(),
                                )
                                .publishPercentiles(0.9, 0.95, 0.99)
                                .register(meterRegistry)
                                .record(java.time.Duration.ofNanos(callDuration))

                            incMetric(statusCode.toString())
                            markProcessed(body.result, txId, body.message)

                            

                            if (statusCode == 200 && body.result) {
                                // Track at which retry the payment finally succeeded
                                meterRegistry.counter(
                                    "payment_success_attempt_total",
                                    "target", paymentProviderHostPort,
                                    "account", accountName,
                                    "attempt", attempt.toString()
                                ).increment()
                                return
                            }

                            val retryable = (statusCode == 200 && !body.result) || statusCode == 429 || statusCode in 500..599
                            if (!retryable) return

                            val cause = when {
                                statusCode == 200 && !body.result -> "provider_false"
                                statusCode == 429 -> "http_429"
                                statusCode in 500..599 -> "http_5xx"
                                else -> "other"
                            }
                            meterRegistry.counter(
                                "payment_provider_retries_total",
                                "target", paymentProviderHostPort,
                                "account", accountName,
                                "cause", cause
                            ).increment()

                            if (statusCode == 429) Thread.sleep(ratePollIntervalMs * 10)
                        }
                    } catch (e: Exception) {
                        logger.error("[$accountName] Call exception for txId: $txId, payment: $paymentId", e)
                        markProcessed(false, txId, e.message)
                        incMetric("exception")
                        meterRegistry.counter(
                            "payment_provider_retries_total",
                            "target", paymentProviderHostPort,
                            "account", accountName,
                            "cause", "exception"
                        ).increment()
                        if (callStartNs != 0L) {
                            val callDuration = System.nanoTime() - callStartNs
                            Timer
                                .builder("payment_provider_request_duration_seconds")
                                .tags(
                                    "target", paymentProviderHostPort,
                                    "account", accountName,
                                    "status", "exception",
                                )
                                .publishPercentiles(0.9, 0.95, 0.99)
                                .register(meterRegistry)
                                .record(java.time.Duration.ofNanos(callDuration))
                        }
                    }
                } finally {
                    if (acquired) semaphore.release()
                }
                attempt += 1
            }
        } catch (e: Exception) {
            when (e) {
                is ResponseStatusException -> return
                is SocketTimeoutException -> {
                    logger.error("[$accountName] Payment timeout for payment: $paymentId", e)
                    incMetric("timeout")
                }
                else -> {
                    logger.error("[$accountName] Payment failed for payment: $paymentId", e)
                    incMetric("exception")
                }
            }
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

public fun now() = System.currentTimeMillis()
