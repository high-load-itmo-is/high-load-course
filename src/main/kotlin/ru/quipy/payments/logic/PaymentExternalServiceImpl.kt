package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.sync.Semaphore
import okhttp3.*
import org.slf4j.LoggerFactory
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.server.ResponseStatusException
import ru.quipy.common.utils.TokenBucketRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

//class TooManyRequestsException(message: String = "Too Many Requests") : RuntimeException(message)
//
//
//@ControllerAdvice
//class GlobalExceptionHandler {
//
//    @ExceptionHandler(TooManyRequestsException::class)
//    fun handleTooManyRequestsException(ex: TooManyRequestsException): ResponseEntity<String> {
//        return ResponseEntity
//            .status(HttpStatus.TOO_MANY_REQUESTS) // HTTP 429
//            .header("Retry-After", "10") // Опционально: заголовок для указания времени ожидания
//            .body(ex.message)
//    }
//}

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
        bucketMaxCapacity = maxOf(rateLimitPerSec, parallelRequests / 2),
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

        fun remaining(): Long = deadline - now()

        try {
            while (true) {
                if (remaining() <= minBudgetMsForAttempt) {
                    val tx = UUID.randomUUID()
                    paymentESService.update(paymentId) {
                        it.logSubmission(false, tx, now(), Duration.ofMillis(now() - paymentStartedAt))
                        it.logProcessing(false, now(), tx, reason = "Insufficient time budget before call")
                    }
                    return
                }

                val transactionId = UUID.randomUUID()
                paymentESService.update(paymentId) {
                    it.logSubmission(true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
                }

                var acquired = false
                try {
                    while (!semaphore.tryAcquire()) {
                        if (remaining() <= minBudgetMsForAttempt) {
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, reason = "Insufficient time budget in queue")
                            }
                            return
                        }
                        Thread.sleep(acquirePollIntervalMs)
                    }
                    acquired = true

                    while (!rateLimiter.tick()) {
                        if (remaining() <= minBudgetMsForAttempt) {
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, reason = "Insufficient time budget due to rate limiting")
                            }
                            return
                        }
                        Thread.sleep(ratePollIntervalMs)
                    }

                    val timeBudgetMs = remaining() - safetyMarginMs
                    if (timeBudgetMs <= 0) {
                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now(), transactionId, reason = "No time budget after acquiring permit")
                        }
                        return
                    }

                    val request = Request.Builder().run {
                        url("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
                        post(emptyBody)
                    }.build()

                    val callClient = client.newBuilder()
                        .callTimeout(timeBudgetMs, TimeUnit.MILLISECONDS)
                        .readTimeout(timeBudgetMs, TimeUnit.MILLISECONDS)
                        .writeTimeout(timeBudgetMs, TimeUnit.MILLISECONDS)
                        .connectTimeout(minOf(timeBudgetMs, 1000L).toInt().toLong(), TimeUnit.MILLISECONDS)
                        .build()

                    try {
                        callClient.newCall(request).execute().use { response ->
                            val status = response.code
                            val body = try {
                                mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
                            } catch (e: Exception) {
                                logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                                ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                            }

                            logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

                            meterRegistry.counter(
                                "service_outgoing_requests_total",
                                "target", paymentProviderHostPort,
                                "account", accountName,
                                "status", status.toString()
                            ).increment()

                            paymentESService.update(paymentId) {
                                it.logProcessing(body.result, now(), transactionId, reason = body.message)
                            }

                            if (status == 200 && body.result) {
                                return
                            }

                            val retryable = (status == 200 && !body.result) || status == 429 || status in 500..599
                            if (!retryable) {
                                return
                            }
                            if (status == 429) {
                                Thread.sleep(ratePollIntervalMs * 10)
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("[$accountName] Call exception for txId: $transactionId, payment: $paymentId", e)
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
                } finally {
                    if (acquired) semaphore.release()
                }
            }
        } catch (e: Exception) {
            when (e) {
                is ResponseStatusException -> {
                    return
                }
                is SocketTimeoutException -> {
                    logger.error("[$accountName] Payment timeout for payment: $paymentId", e)
                    meterRegistry.counter(
                        "service_outgoing_requests_total",
                        "target", paymentProviderHostPort,
                        "account", accountName,
                        "status", "timeout"
                    ).increment()
                }
                else -> {
                    logger.error("[$accountName] Payment failed for payment: $paymentId", e)
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
