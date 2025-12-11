package ru.quipy.payments.logic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        
        // Fire and forget ES create - don't block the controller thread
        GlobalScope.launch(Dispatchers.IO) {
            try {
                paymentESService.create {
                    it.create(paymentId, orderId, amount)
                }
                logger.trace("Payment $paymentId for order $orderId created.")
            } catch (e: Exception) {
                logger.error("Error creating payment $paymentId for order $orderId", e)
            }
        }

        // Submit to payment service immediately - don't wait for ES create
        paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
        
        return createdAt
    }
}
