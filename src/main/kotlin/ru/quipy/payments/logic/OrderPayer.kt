package ru.quipy.payments.logic

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.time.Duration
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service
class OrderPayer {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    @Autowired
    private lateinit var paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>

    @Autowired
    private lateinit var paymentService: PaymentService

    @Autowired
    private lateinit var paymentAccounts: List<PaymentExternalSystemAdapter>

    private val parallelRequests: Int by lazy { paymentAccounts.first().parallelRequests() }
    private val rateLimitPerSec: Int by lazy { paymentAccounts.first().rateLimitPerSec() }
    private val requestAverageProcessingTime: Duration by lazy { paymentAccounts.first().averageProcessingTime() }

    fun calculateMaxQueueSize(): Int {
        val calculatedSize = (1000 / requestAverageProcessingTime.toMillis() * parallelRequests).toInt()
        val minSize = minOf(rateLimitPerSec, calculatedSize)
        logger.info("Max queue size: $minSize")
        return minSize
    }

    private val rejectedExecutionHandler = RejectedExecutionHandler { _, _ ->
        logger.warn("Payment executor queue is full, rejecting request")
        throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Payment queue is full, please try again later")
    }

    private val paymentExecutor by lazy {
        ThreadPoolExecutor(
            16,
            16,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(calculateMaxQueueSize()),
            NamedThreadFactory("payment-submission-executor"),
            rejectedExecutionHandler
        )
    }

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        val future = paymentExecutor.submit {
            val createdEvent = paymentESService.create {
                it.create(paymentId, orderId, amount)
            }
            logger.trace("Payment ${createdEvent.paymentId} for order $orderId created.")

            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
        }

        future.get()

        return createdAt
    }
}