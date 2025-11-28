package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.sync.Semaphore
import okhttp3.*
import org.slf4j.LoggerFactory
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit


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

    private val rateLimiter = SlidingWindowRateLimiter(
        rateLimitPerSec.toLong(),
        Duration.ofSeconds(1)
    )
    private val semaphore = Semaphore(parallelRequests);

    private val client = OkHttpClient.Builder()
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")

        val transactionId = UUID.randomUUID()

        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

        try {
            val result = executePaymentWithRetry(paymentId, amount, transactionId, maxAttempts = 3)

            paymentESService.update(paymentId) {
                it.logProcessing(result.success, now(), transactionId, reason = result.message)
            }
        } catch (e: Exception) {
            when (e) {
                is SocketTimeoutException -> {
                    logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
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

                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = e.message)
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

    private fun executePaymentWithRetry(
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

            val request = Request.Builder().run {
                url("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
                post(emptyBody)
            }.build()

            while (!semaphore.tryAcquire()) {
                Thread.sleep(10)
            }

            try {
                rateLimiter.tickBlocking()

                // Start measuring request time
                val startTime = System.nanoTime()

                client.newCall(request).execute().use { response ->
                    // Calculate request duration
                    val duration = System.nanoTime() - startTime

                    val body = try {
                        mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
                    } catch (e: Exception) {
                        logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                        ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                    }

                    logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}, attempt: $attempt")

                    // Record request latency with quantiles
                    Timer.builder("payment_request_latency")
                        .description("Payment request latency in milliseconds")
                        .tag("target", paymentProviderHostPort)
                        .tag("account", accountName)
                        .tag("status_code", response.code.toString())
                        .tag("result", body.result.toString())
                        .publishPercentiles(0.5, 0.8, 0.95, 0.99) // p50, p80, p95, p99
                        .register(meterRegistry)
                        .record(duration, TimeUnit.NANOSECONDS)

                    meterRegistry.counter(
                        "service_outgoing_requests_total",
                        "target", paymentProviderHostPort,
                        "account", accountName,
                        "status", response.code.toString()
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
                }
            } finally {
                semaphore.release()
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
