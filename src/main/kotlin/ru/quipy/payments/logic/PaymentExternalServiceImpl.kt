package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.WebClientResponseException.TooManyRequests
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.http.HttpProtocol
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.HttpClientRequest
import reactor.netty.resources.ConnectionProvider
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException


class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val meterRegistry: MeterRegistry,
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()

        private const val MAX_CONNECTIONS = 128
        private const val WARMUP_CONNECTIONS = 32
        private const val DEADLINE_SAFETY_BUFFER_MS = 75L
        private const val MINIMUM_ATTEMPT_BUDGET_MS = 250L
        private const val MEDIUM_HEDGE_THRESHOLD = 0.75

        val connectionProvider: ConnectionProvider = ConnectionProvider.builder("payment-provider")
            .maxConnections(MAX_CONNECTIONS)
            .pendingAcquireTimeout(Duration.ofSeconds(120))
            .maxIdleTime(Duration.ofSeconds(60))
            .build()

        val sharedHttpClient: HttpClient = HttpClient.create(connectionProvider)
            .protocol(HttpProtocol.H2C)
            .responseTimeout(Duration.ofMillis(120000))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)

        private val sharedDispatcher = Executors.newFixedThreadPool(
            64,
            NamedThreadFactory("payment-worker")
        ).asCoroutineDispatcher()

        val paymentScope = CoroutineScope(SupervisorJob() + sharedDispatcher)
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val rateLimiter = SlidingWindowRateLimiter(
        rateLimitPerSec.toLong(),
        Duration.ofSeconds(1)
    )
    private val semaphore = Semaphore(parallelRequests)

    private val outgoingCounters = ConcurrentHashMap<String, Counter>()
    private val externalDurationSummaries = ConcurrentHashMap<String, DistributionSummary>()

    private val hedgeAttemptCounter = meterRegistry.counter(
        "payment_hedge_attempts_total",
        "service", serviceName,
        "accountName", accountName
    )
    private val hedgeWinCounter = meterRegistry.counter(
        "payment_hedge_wins_total",
        "service", serviceName,
        "accountName", accountName
    )
    private val hedgeCancellationCounter = meterRegistry.counter(
        "payment_hedge_cancellations_total",
        "service", serviceName,
        "accountName", accountName
    )
    private val businessRetryCounter = meterRegistry.counter(
        "payment_business_retries_total",
        "service", serviceName,
        "accountName", accountName
    )
    private val exhaustedRequestCounter = meterRegistry.counter(
        "payment_attempts_exhausted_total",
        "service", serviceName,
        "accountName", accountName
    )

    init {
        logger.warn("[$accountName] Initialized with rateLimitPerSec=$rateLimitPerSec, parallelRequests=$parallelRequests")
    }

    private val webClient: WebClient = WebClient.builder()
        .baseUrl("http://$paymentProviderHostPort")
        .clientConnector(ReactorClientHttpConnector(sharedHttpClient))
        .build()

    fun preWarmConnection() {
        val warmupTimeout = Duration.ofMillis(700)
        val jobs = LinkedList<Job>()
        repeat(WARMUP_CONNECTIONS) {
            jobs.add(
                paymentScope.launch {
                    try {
                        webClient.get()
                            .uri { uriBuilder ->
                                uriBuilder.path("/external/accounts")
                                    .queryParam("serviceName", serviceName)
                                    .queryParam("token", token)
                                    .build()
                            }
                            .httpRequest { request ->
                                request
                                    .getNativeRequest<HttpClientRequest>()
                                    .responseTimeout(warmupTimeout)
                            }
                            .retrieve()
                            .bodyToMono<String>()
                            .awaitSingle()
                    } catch (e: Exception) {
                    }
                }
            )
        }
        runBlocking {
            jobs.joinAll()
        }
        logger.info("[$accountName] Pre-warmed external provider connection")
    }

    override fun performPaymentAsync(orderId: UUID, paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) : Job {
        return paymentScope.launch {
            executePaymentReactive(paymentId, amount, deadline)
        }
    }

    private suspend fun executePaymentReactive(paymentId: UUID, amount: Int, deadline: Long) = supervisorScope {
        val attemptDelays = buildAttemptDelays(deadline)
        if (attemptDelays.isEmpty()) {
            exhaustedRequestCounter.increment()
            return@supervisorScope
        }

        val outcomes = Channel<AttemptOutcome>(Channel.UNLIMITED)
        val attemptJobs = attemptDelays.mapIndexed { attemptNumber: Int, delayMillis: Long ->
            launch {
                try {
                    if (delayMillis > 0) {
                        delay(delayMillis)
                    }

                    ensureActive()
                    outcomes.send(
                        executeSinglePaymentAttempt(
                            paymentId = paymentId,
                            amount = amount,
                            deadline = deadline,
                            attemptNumber = attemptNumber,
                        )
                    )
                } finally {
                    if (!isActive && delayMillis > 0) {
                        hedgeCancellationCounter.increment()
                    }
                }
            }
        }

        var lastFailure: AttemptOutcome.Failure? = null
        repeat(attemptJobs.size) {
            when (val outcome = outcomes.receive()) {
                is AttemptOutcome.Success -> {
                    if (outcome.attemptNumber > 0) {
                        hedgeWinCounter.increment()
                    }
                    attemptJobs.forEach { job: Job ->
                        if (job.isActive) {
                            job.cancel()
                        }
                    }
                    return@supervisorScope
                }

                is AttemptOutcome.Failure -> lastFailure = outcome
            }
        }

        exhaustedRequestCounter.increment()
        logger.debug(
            "[$accountName] Exhausted ${attemptJobs.size} attempt(s) for payment $paymentId before deadline, last outcome=${lastFailure?.outcome}, reason=${lastFailure?.reason}"
        )
    }

    private suspend fun executeSinglePaymentAttempt(
        paymentId: UUID,
        amount: Int,
        deadline: Long,
        attemptNumber: Int,
    ): AttemptOutcome {
        val startedAt = now()
        val timeoutMillis = deadline - startedAt - DEADLINE_SAFETY_BUFFER_MS
        if (timeoutMillis <= MINIMUM_ATTEMPT_BUDGET_MS) {
            return AttemptOutcome.Failure(
                attemptNumber = attemptNumber,
                outcome = "deadline_budget_exhausted",
                reason = "Not enough time left to send another attempt",
            )
        }

        val transactionId = UUID.randomUUID()
        if (attemptNumber > 0) {
            hedgeAttemptCounter.increment()
        }

        if (!semaphore.tryAcquire()) {
            return AttemptOutcome.Failure(
                attemptNumber = attemptNumber,
                outcome = "local_parallel_limit",
                reason = "Semaphore rejected the attempt",
            )
        }
        if (!rateLimiter.tick()) {
            semaphore.release()
            return AttemptOutcome.Failure(
                attemptNumber = attemptNumber,
                outcome = "local_rate_limit",
                reason = "Client-side rate limiter rejected the attempt",
            )
        }

        try {
            val responseBody = webClient.post()
                .uri { uriBuilder ->
                    uriBuilder.path("/external/process")
                        .queryParam("serviceName", serviceName)
                        .queryParam("token", token)
                        .queryParam("accountName", accountName)
                        .queryParam("transactionId", transactionId)
                        .queryParam("paymentId", paymentId)
                        .queryParam("amount", amount)
                        .build()
                }
                .httpRequest { request ->
                    request
                        .getNativeRequest<HttpClientRequest>()
                        .responseTimeout(Duration.ofMillis(timeoutMillis))
                }
                .retrieve()
                .bodyToMono<String>()
                .awaitSingle()

            val durationMillis = now() - startedAt
            val body = try {
                mapper.readValue(responseBody, ExternalSysResponse::class.java)
            } catch (e: Exception) {
                recordExternalAttempt("parse_error", durationMillis)
                return AttemptOutcome.Failure(
                    attemptNumber = attemptNumber,
                    outcome = "parse_error",
                    reason = e.message,
                )
            }

            return if (body.result) {
                recordExternalAttempt("success", durationMillis)
                AttemptOutcome.Success(attemptNumber)
            } else {
                businessRetryCounter.increment()
                recordExternalAttempt("business_false", durationMillis)
                AttemptOutcome.Failure(
                    attemptNumber = attemptNumber,
                    outcome = "business_false",
                    reason = body.message,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TooManyRequests) {
            val durationMillis = now() - startedAt
            recordExternalAttempt("http_429", durationMillis)
            return AttemptOutcome.Failure(
                attemptNumber = attemptNumber,
                outcome = "http_429",
                reason = e.message,
            )
        } catch (e: WebClientResponseException) {
            val durationMillis = now() - startedAt
            val outcome = "http_${e.statusCode.value()}"
            recordExternalAttempt(outcome, durationMillis)
            return AttemptOutcome.Failure(
                attemptNumber = attemptNumber,
                outcome = outcome,
                reason = e.responseBodyAsString.ifBlank { e.message },
            )
        } catch (e: Exception) {
            val durationMillis = now() - startedAt
            val outcome = if (isTimeout(e)) "timeout" else "exception"
            recordExternalAttempt(outcome, durationMillis)
            return AttemptOutcome.Failure(
                attemptNumber = attemptNumber,
                outcome = outcome,
                reason = e.message,
            )
        } finally {
            semaphore.release()
        }
    }

    private fun buildAttemptDelays(deadline: Long): List<Long> {
        val remainingBudgetMillis = deadline - now() - DEADLINE_SAFETY_BUFFER_MS
        if (remainingBudgetMillis <= MINIMUM_ATTEMPT_BUDGET_MS) {
            return emptyList()
        }

        val averageProcessingMillis = properties.averageProcessingTime.toMillis().coerceAtLeast(1)
        val spacingMillis = ((remainingBudgetMillis - MINIMUM_ATTEMPT_BUDGET_MS) / 6).coerceIn(75L, 150L)

        val attemptPlan = when {
            averageProcessingMillis >= remainingBudgetMillis -> listOf(
                0L,
                spacingMillis,
                spacingMillis * 2,
                spacingMillis * 3,
            )

            averageProcessingMillis >= (remainingBudgetMillis * MEDIUM_HEDGE_THRESHOLD).toLong() -> listOf(
                0L,
                (spacingMillis * 2).coerceAtLeast(100L),
            )

            else -> listOf(0L)
        }

        return attemptPlan.filter { delayMillis ->
            delayMillis + MINIMUM_ATTEMPT_BUDGET_MS < remainingBudgetMillis
        }
    }

    private fun recordExternalAttempt(outcome: String, durationMillis: Long) {
        outgoingCounter(outcome).increment()
        externalDurationSummary(outcome).record(durationMillis.toDouble())
    }

    private fun outgoingCounter(status: String): Counter {
        return outgoingCounters.computeIfAbsent(status) {
            meterRegistry.counter(
                "service_outgoing_requests_total",
                "service", serviceName,
                "target", paymentProviderHostPort,
                "accountName", accountName,
                "status", status
            )
        }
    }

    private fun externalDurationSummary(outcome: String): DistributionSummary {
        return externalDurationSummaries.computeIfAbsent(outcome) {
            DistributionSummary.builder("external_sys_duration")
                .tags(
                    "service", serviceName,
                    "accountName", accountName,
                    "outcome", outcome
                )
                .register(meterRegistry)
        }
    }

    private fun isTimeout(error: Throwable): Boolean {
        return when (error) {
            is TimeoutException,
            is SocketTimeoutException,
            is ReadTimeoutException -> true

            is WebClientRequestException -> error.cause?.let(::isTimeout) ?: false
            else -> error.cause?.takeIf { it !== error }?.let(::isTimeout) ?: false
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName
}

private sealed interface AttemptOutcome {
    val attemptNumber: Int

    data class Success(
        override val attemptNumber: Int,
    ) : AttemptOutcome

    data class Failure(
        override val attemptNumber: Int,
        val outcome: String,
        val reason: String?,
    ) : AttemptOutcome
}

data class PaymentResult(val success: Boolean, val message: String?)

public fun now() = System.currentTimeMillis()
