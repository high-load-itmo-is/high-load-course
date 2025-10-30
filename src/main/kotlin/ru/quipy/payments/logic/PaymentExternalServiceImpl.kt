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
import java.util.concurrent.atomic.AtomicLong

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

    // Adaptive estimate of provider processing time (ms), EWMA-updated
    private val expectedProcMs = AtomicLong(
        maxOf(requestAverageProcessingTime.toMillis(), 2500L)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .dispatcher(Dispatcher().apply {
            // Lift per-host concurrency limits for async calls
            maxRequests = 256
            maxRequestsPerHost = 256
        })
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

        var acquired = false
        try {
            // Compute remaining time budget before the deadline (excluding average processing)
            val expMs = (expectedProcMs.get() * 1.15).toLong() // safety margin
            val budgetMs = (deadline - now() - expMs).coerceAtLeast(0)
            if (budgetMs == 0L) {
                throw TooManyRequestsException(retryAfterSeconds = 1)
            }

            // If token wait exceeds our budget, reject early with Retry-After
            val tokenWait = rateLimiter.estimateWaitTimeMillis()
            if (tokenWait > budgetMs) {
                val seconds = maxOf(1L, kotlin.math.ceil(tokenWait / 1000.0).toLong())
                throw TooManyRequestsException(retryAfterSeconds = seconds)
            }

            // Acquire semaphore within remaining budget after token wait
            val semWait = (budgetMs - tokenWait).coerceAtLeast(0)
            acquired = semaphore.tryAcquire(semWait, TimeUnit.MILLISECONDS)
            if (!acquired) {
                throw TooManyRequestsException(retryAfterSeconds = 1)
            }

            // Pace to provider rate (11 rps) to avoid breaches
            rateLimiter.tickBlocking()

            val callStartTs = now()
            val call = client.newCall(request)
            // Optional per-call timeout budget; keep generous to avoid premature timeouts
            // call.timeout().timeout(30, TimeUnit.SECONDS)

            call.enqueue(object : Callback {
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

                    // Update processing time estimate with EWMA
                    val measured = (now() - callStartTs).coerceAtLeast(1L)
                    updateExpectedProc(measured)

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
        } catch (e: TooManyRequestsException) {
            throw e
        } catch (e: SocketTimeoutException) {
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
            if (acquired) semaphore.release()
        } catch (e: Exception) {
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
            if (acquired) semaphore.release()
        }
    }

    private fun updateExpectedProc(measuredMs: Long) {
        val clamped = measuredMs.coerceIn(500L, 30_000L)
        expectedProcMs.getAndUpdate { prev ->
            val ewma = (prev * 0.8 + clamped * 0.2).toLong()
            ewma
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

public fun now() = System.currentTimeMillis()
