package ru.quipy.payments.logic

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Pair<Long, List<Job>> {
        val createdAt = System.currentTimeMillis()
        val jobs = paymentService.submitPaymentRequest(orderId, paymentId, amount, createdAt, deadline)
        return Pair(createdAt, jobs)
    }
}
