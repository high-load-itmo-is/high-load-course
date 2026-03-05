package ru.quipy.payments.logic

import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*


@Service
class PaymentSystemImpl(
    private val paymentAccounts: List<PaymentExternalSystemAdapter>
) : PaymentService {
    companion object {
        val logger = LoggerFactory.getLogger(PaymentSystemImpl::class.java)
    }

    override suspend fun submitPaymentRequest(orderId: UUID, paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) : List<Job> {
        val jobs = mutableListOf<Job>()
        for (account in paymentAccounts) {
            val paymentJob = account.performPaymentAsync(orderId, paymentId, amount, paymentStartedAt, deadline)
            jobs.add(paymentJob)
        }
        return jobs
    }
}