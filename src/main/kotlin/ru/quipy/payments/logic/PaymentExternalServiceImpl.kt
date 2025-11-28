package ru.quipy.payments.logic

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.*
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
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
    private val semaphore = Semaphore(parallelRequests)

    // Dedicated executor and dispatcher for outbound HTTP calls, tuned for high concurrency
    private val httpExecutor = Executors.newCachedThreadPool(
        NamedThreadFactory("payment-http-$accountName")
    )

    private val dispatcher = Dispatcher(httpExecutor).apply {
        // Bound the number of in-flight HTTP calls for this account
        maxRequests = parallelRequests
        maxRequestsPerHost = parallelRequests
    }

    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .callTimeout(Duration.ofMillis(20000))
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")

        val transactionId = UUID.randomUUID()

        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

        // Execute payment in a fully asynchronous fashion; retries and result handling
        // happen in HTTP client callbacks, so submission threads are not blocked
        executePaymentWithRetryAsync(paymentId, amount, transactionId, maxAttempts = 3)
    }

    private fun executePaymentWithRetryAsync(
        paymentId: UUID,
        amount: Int,
        transactionId: UUID,
        maxAttempts: Int,
        attempt: Int = 1,
        lastResult: PaymentResult? = null,
    ) {
        if (attempt > maxAttempts) {
            val finalResult = lastResult ?: PaymentResult(false, "All retry attempts failed")

            val retryCount = attempt - 2 // attempts are 1-based and we already exceeded maxAttempts
            if (retryCount > 0) {
                recordRetries(retryCount, if (finalResult.success) "success" else "failed")
            }

            logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId. All retry attempts exhausted. Reason: ${finalResult.message}")

            paymentESService.update(paymentId) {
                it.logProcessing(finalResult.success, now(), transactionId, reason = finalResult.message)
            }
            return
        }

        logger.info("[$accountName] Attempt $attempt/$maxAttempts for payment $paymentId, txId: $transactionId")

        val request = Request.Builder().run {
            url("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
            post(emptyBody)
        }.build()

        try {
            // Limit concurrent requests and overall rate before enqueuing the HTTP call
            semaphore.acquire()
            rateLimiter.tickBlocking()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.error("[$accountName] Payment processing interrupted for txId: $transactionId, payment: $paymentId", e)
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = "Interrupted while acquiring permits")
            }
            return
        }

        // Start measuring request time
        val startTime = System.nanoTime()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                try {
                    when (e) {
                        is InterruptedIOException -> {
                            if (attempt < maxAttempts) {
                                logger.warn("[$accountName] Payment request had timeout for txId: $transactionId, payment: $paymentId. Retrying... (attempt $attempt/$maxAttempts)")
                                executePaymentWithRetryAsync(paymentId, amount, transactionId, maxAttempts, attempt + 1, lastResult)
                            } else {
                                logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId after $attempt attempts", e)
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
                        }

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
                } finally {
                    semaphore.release()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    // Calculate request duration
                    val duration = System.nanoTime() - startTime

                    val body = try {
                        mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
                    } catch (e: Exception) {
                        logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                        ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                    }

                    logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}, attempt: $attempt")

                    // Record request latency
                    Timer.builder("payment_request_latency_seconds")
                        .description("Payment request latency")
                        .tags(
                            "target", paymentProviderHostPort,
                            "account", accountName,
                            "status_code", response.code.toString(),
                            "result", body.result.toString()
                        )
                        .publishPercentileHistogram()
                        .register(meterRegistry)
                        .record(duration, TimeUnit.NANOSECONDS)

                    meterRegistry.counter(
                        "service_outgoing_requests_total",
                        "target", paymentProviderHostPort,
                        "account", accountName,
                        "status", response.code.toString()
                    ).increment()

                    val result = PaymentResult(body.result, body.message)

                    if (body.result) {
                        val retryCount = attempt - 1
                        if (retryCount > 0) {
                            recordRetries(retryCount, "success")
                        }

                        paymentESService.update(paymentId) {
                            it.logProcessing(true, now(), transactionId, reason = body.message)
                        }
                    } else {
                        if (attempt < maxAttempts) {
                            logger.warn("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId. Retrying...")
                            executePaymentWithRetryAsync(paymentId, amount, transactionId, maxAttempts, attempt + 1, result)
                        } else {
                            val retryCount = attempt - 1
                            if (retryCount > 0) {
                                recordRetries(retryCount, "failed")
                            }

                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, reason = body.message)
                            }
                        }
                    }
                } finally {
                    semaphore.release()
                }
            }
        })
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
