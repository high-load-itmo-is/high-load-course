package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.util.concurrent.Semaphore
import okhttp3.*
import org.slf4j.LoggerFactory
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.server.ResponseStatusException
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

class TooManyRequestsException(
    val retryAfterSeconds: Long = 1L,
    message: String = "Too Many Requests",
) : RuntimeException(message)

@ControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(TooManyRequestsException::class)
    fun handleTooManyRequestsException(ex: TooManyRequestsException): ResponseEntity<String> {
        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", ex.retryAfterSeconds.toString())
            .body(ex.message)
    }
}

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

    private val rateLimiter = SlidingWindowRateLimiter(
        rateLimitPerSec.toLong(),
        Duration.ofSeconds(1)
    )
    private val semaphore = Semaphore(parallelRequests);

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")

        val transactionId = UUID.randomUUID()

        // Mark submission for the tester in all cases
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

        val request = Request.Builder().run {
            url("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
            post(emptyBody)
        }.build()

        // Acquire permit (blocking) to bound concurrency
        semaphore.acquire()

        // Enforce egress rate; if rejected, release and advise Retry-After
        if (!rateLimiter.tick()) {
            val retryAfterMs = rateLimiter.estimateWaitTimeMillis()
            val seconds = maxOf(1L, kotlin.math.ceil(retryAfterMs / 1000.0).toLong())
            semaphore.release()
            throw TooManyRequestsException(retryAfterSeconds = seconds)
        }

        // Send asynchronously to free executor threads immediately
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
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

                semaphore.release()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = try {
                        mapper.readValue(resp.body?.string(), ExternalSysResponse::class.java)
                    } catch (e: Exception) {
                        logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${resp.code}, reason: ${resp.body?.string()}")
                        ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                    }

                    logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

                    meterRegistry.counter(
                        "service_outgoing_requests_total",
                        "target", paymentProviderHostPort,
                        "account", accountName,
                        "status", resp.code.toString()
                    ).increment()

                    paymentESService.update(paymentId) {
                        it.logProcessing(body.result, now(), transactionId, reason = body.message)
                    }
                }
                semaphore.release()
            }
        })
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

public fun now() = System.currentTimeMillis()
