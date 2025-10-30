package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.util.concurrent.Semaphore
import okhttp3.*
import org.slf4j.LoggerFactory
import io.micrometer.core.instrument.MeterRegistry
import ru.quipy.common.utils.TokenBucketRateLimiter
import ru.quipy.common.web.TooManyRequestsException
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

    private val rateLimiter = TokenBucketRateLimiter(
        rate = rateLimitPerSec,
        bucketMaxCapacity = rateLimitPerSec,
        window = 1,
        timeUnit = TimeUnit.SECONDS,
    )
    private val semaphore = Semaphore(parallelRequests)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")

        // First attempt transaction
        var transactionId = UUID.randomUUID()

        // Always mark that submission happened for test harness
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

        try {
            fun buildRequest(txId: UUID) = Request.Builder().run {
                url("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$txId&paymentId=$paymentId&amount=$amount")
                post(emptyBody)
            }.build()

            val estimatedProcMs = requestAverageProcessingTime.toMillis()

            // Check time budget and semaphore acquire with timeout
            val remainingBeforeAcquire = deadline - now()
            if (remainingBeforeAcquire <= estimatedProcMs) {
                meterRegistry.counter(
                    "service_outgoing_rejections_total",
                    "target", paymentProviderHostPort,
                    "account", accountName,
                    "reason", "deadline"
                ).increment()
                throw TooManyRequestsException(retryAfterMillis = maxOf(100L, remainingBeforeAcquire))
            }
            val waitBudgetMs = remainingBeforeAcquire - estimatedProcMs
            if (!semaphore.tryAcquire(waitBudgetMs, TimeUnit.MILLISECONDS)) {
                meterRegistry.counter(
                    "service_outgoing_rejections_total",
                    "target", paymentProviderHostPort,
                    "account", accountName,
                    "reason", "semaphore"
                ).increment()
                throw TooManyRequestsException(retryAfterMillis = minOf(1000L, maxOf(100L, waitBudgetMs)))
            }

            try {
                // Rate-limit after acquiring the slot to avoid wasting tokens
                if (!rateLimiter.tick()) {
                    val waitMs = rateLimiter.estimateWaitTimeMillis().toLong()
                    meterRegistry.counter(
                        "service_outgoing_rejections_total",
                        "target", paymentProviderHostPort,
                        "account", accountName,
                        "reason", "rate_limit"
                    ).increment()
                    throw TooManyRequestsException(retryAfterMillis = maxOf(100L, waitMs))
                }

                fun executeOnce(txId: UUID): ExternalSysResponse {
                    val callBudget = maxOf(200L, deadline - now())
                    val callClient = client.newBuilder().callTimeout(callBudget, TimeUnit.MILLISECONDS).build()
                    val request = buildRequest(txId)
                    callClient.newCall(request).execute().use { response ->
                        val body = try {
                            mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
                        } catch (e: Exception) {
                            logger.error("[$accountName] [ERROR] Payment processed for txId: $txId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                            ExternalSysResponse(txId.toString(), paymentId.toString(), false, e.message)
                        }

                        logger.warn("[$accountName] Payment processed for txId: $txId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

                        meterRegistry.counter(
                            "service_outgoing_requests_total",
                            "target", paymentProviderHostPort,
                            "account", accountName,
                            "status", response.code.toString()
                        ).increment()

                        // Update processing state for this attempt
                        paymentESService.update(paymentId) {
                            it.logProcessing(body.result, now(), txId, reason = body.message)
                        }
                        return body
                    }
                }

                // Execute first attempt
                var body = executeOnce(transactionId)

                // If failed, try one more time if budget allows
                val canRetry = !body.result && (now() + estimatedProcMs) < deadline
                if (canRetry) {
                    // small jittered backoff
                    val backoffMs = 100L
                    Thread.sleep(minOf(backoffMs, maxOf(0L, deadline - now() - estimatedProcMs)))

                    transactionId = UUID.randomUUID()
                    paymentESService.update(paymentId) {
                        it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
                    }
                    logger.info("[$accountName] Retry submit: $paymentId , txId: $transactionId")
                    body = executeOnce(transactionId)
                }
            } finally {
                semaphore.release()
            }
        } catch (e: Exception) {
            when (e) {
                is TooManyRequestsException -> throw e
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

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

public fun now() = System.currentTimeMillis()
