package ru.quipy.payments.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.*
import java.util.concurrent.Executors

@Service
class OrderPayer {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    @Autowired
    private lateinit var paymentService: PaymentService

    private val sharedDispatcher = Executors.newFixedThreadPool(
        64,
        NamedThreadFactory("payment-worker")
    ).asCoroutineDispatcher()

    val paymentScope = CoroutineScope(SupervisorJob() + sharedDispatcher)

    suspend fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        paymentScope.launch {
            paymentService.submitPaymentRequest(orderId, paymentId, amount, createdAt, deadline)
        }
        return createdAt
    }
}
