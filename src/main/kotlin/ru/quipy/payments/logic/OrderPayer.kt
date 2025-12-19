package ru.quipy.payments.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.*
import java.util.concurrent.Executors
import ru.quipy.common.utils.NamedThreadFactory

@Service
class OrderPayer {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    @Autowired
    private lateinit var paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>

    @Autowired
    private lateinit var paymentService: PaymentService

    private val dispatcher = Executors.newFixedThreadPool(
        64,
        NamedThreadFactory("payment-worker")
    ).asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    suspend fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()

        try {
            paymentESService.create {
                it.create(paymentId, orderId, amount)
            }
            logger.trace("Payment $paymentId for order $orderId created.")
        } catch (e: Exception) {
            logger.error("Error creating payment $paymentId for order $orderId", e)
        }

        scope.launch {
            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
        }

        return createdAt
    }
}
