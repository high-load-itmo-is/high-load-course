package ru.quipy.apigateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.orders.repository.OrderRepository
import ru.quipy.payments.logic.OrderPayer
import java.time.Duration
import java.util.*
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import ru.quipy.common.utils.NamedThreadFactory
import java.util.concurrent.Executors

class TooManyPaymentRequestsException(
    private val retryAfterSeconds: Long
) : ResponseStatusException(
    HttpStatus.TOO_MANY_REQUESTS,
    "Too many payment requests"
) {
    override fun getHeaders(): HttpHeaders {
        val headers = HttpHeaders()
        headers.add(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
        return headers
    }
}

@RestController
class APIController {

    val logger: Logger = LoggerFactory.getLogger(APIController::class.java)

    @Autowired
    private lateinit var orderRepository: OrderRepository

    @Autowired
    private lateinit var orderPayer: OrderPayer

    @PostMapping("/users")
    fun createUser(@RequestBody req: CreateUserRequest): User {
        return User(UUID.randomUUID(), req.name)
    }

    data class CreateUserRequest(val name: String, val password: String)

    data class User(val id: UUID, val name: String)

    @PostMapping("/orders")
    fun createOrder(@RequestParam userId: UUID, @RequestParam price: Int): Order {
        val order = Order(
            UUID.randomUUID(),
            userId,
            System.currentTimeMillis(),
            OrderStatus.COLLECTING,
            price,
        )
        return orderRepository.save(order)
    }

    data class Order(
        val id: UUID,
        val userId: UUID,
        val timeCreated: Long,
        val status: OrderStatus,
        val price: Int,
    )

    enum class OrderStatus {
        COLLECTING,
        PAYMENT_IN_PROGRESS,
        PAID,
    }

    private val rateLimiter = SlidingWindowRateLimiter(
        5000,
        Duration.ofSeconds(1)
    )

    @PostMapping("/orders/{orderId}/payment")
    fun payOrder(@PathVariable orderId: UUID, @RequestParam deadline: Long): PaymentSubmissionDto {
        if (!rateLimiter.tick()) {
            throw TooManyPaymentRequestsException(15)
        }

        val paymentId = UUID.randomUUID()
        val order = orderRepository.findById(orderId)?.let {
            orderRepository.save(it.copy(status = OrderStatus.PAYMENT_IN_PROGRESS))
            it
        } ?: throw IllegalArgumentException("No such order $orderId")

        val createdAt = orderPayer.processPayment(orderId, order.price, paymentId, deadline)
        return PaymentSubmissionDto(createdAt, paymentId)
    }

    class PaymentSubmissionDto(
        val timestamp: Long,
        val transactionId: UUID
    )
}