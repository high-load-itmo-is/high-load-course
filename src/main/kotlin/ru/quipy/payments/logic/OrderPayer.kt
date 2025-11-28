package ru.quipy.payments.logic

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.quipy.common.utils.CallerBlockingRejectedExecutionHandler
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.time.Duration
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service
class OrderPayer(
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentService: PaymentService,
    private val meterRegistry: MeterRegistry,
) {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    private val queueCapacity = 20_000
    private val paymentQueue = LinkedBlockingQueue<Runnable>(queueCapacity)
    private val paymentExecutor = ThreadPoolExecutor(
        200,
        200,
        30L,
        TimeUnit.SECONDS,
        paymentQueue,
        NamedThreadFactory("payment-submission-executor"),
        // Limit caller blocking when the queue is full to avoid stalling API threads for too long
        CallerBlockingRejectedExecutionHandler(Duration.ofSeconds(5))
    )

    init {
        // Track how many payment submissions are queued for processing
        meterRegistry.gauge("payment_submission_queue_size", paymentQueue) { it.size.toDouble() }
        // Track active worker threads processing payments
        meterRegistry.gauge("payment_submission_active_threads", paymentExecutor) { it.activeCount.toDouble() }
    }

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        paymentExecutor.submit {
            val createdEvent = paymentESService.create {
                it.create(
                    paymentId,
                    orderId,
                    amount
                )
            }
            logger.trace("Payment ${createdEvent.paymentId} for order $orderId created.")

            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
        }
        return createdAt
    }
}