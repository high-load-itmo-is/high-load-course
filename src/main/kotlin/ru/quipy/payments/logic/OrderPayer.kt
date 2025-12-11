package ru.quipy.payments.logic

import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.*

@Service
class OrderPayer {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    @Autowired
    private lateinit var paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>

    @Autowired
    private lateinit var paymentService: PaymentService

    // Coroutine scope for async payment processing
    // Uses Default dispatcher - blocking calls will use the shared blockingDispatcher
    private val paymentScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("order-payer")
    )

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        
        // Launch async coroutine - doesn't block, returns immediately
        paymentScope.launch {
            try {
                // Use the shared blocking dispatcher for ES operations
                val createdEvent = withContext(PaymentExternalSystemAdapterImpl.blockingDispatcher) {
                    paymentESService.create {
                        it.create(
                            paymentId,
                            orderId,
                            amount
                        )
                    }
                }
                logger.trace("Payment ${createdEvent.paymentId} for order $orderId created.")

                paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
            } catch (e: Exception) {
                logger.error("Error processing payment $paymentId for order $orderId", e)
            }
        }
        
        return createdAt
    }
}
