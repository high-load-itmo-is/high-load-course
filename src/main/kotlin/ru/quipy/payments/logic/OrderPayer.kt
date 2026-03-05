package ru.quipy.payments.logic

import kotlinx.coroutines.Job
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.*

@Service
class OrderPayer {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    @Autowired
    private lateinit var paymentService: PaymentService

    suspend fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Pair<Long, List<Job>> {
        val createdAt = System.currentTimeMillis()
        return Pair(createdAt, paymentService.submitPaymentRequest(orderId, paymentId, amount, createdAt, deadline))
    }
}
