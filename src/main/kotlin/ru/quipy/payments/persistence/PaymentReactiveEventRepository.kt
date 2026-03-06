package ru.quipy.payments.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import ru.quipy.payments.api.PAYMENT_CREATED_EVENT
import ru.quipy.payments.api.PAYMENT_PROCESSED_EVENT
import ru.quipy.payments.api.PAYMENT_SUBMITTED_EVENT
import ru.quipy.payments.api.PaymentCreatedEvent
import ru.quipy.payments.api.PaymentProcessedEvent
import ru.quipy.payments.api.PaymentSubmittedEvent
import java.time.Duration
import java.util.UUID

@Component
class PaymentReactiveEventRepository(
    private val databaseClient: DatabaseClient,
    private val objectMapper: ObjectMapper,
    @Value("\${event.sourcing.db-schema:event_sourcing_store}")
    private val schemaName: String,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(PaymentReactiveEventRepository::class.java)
        private const val AGGREGATE_TABLE_NAME = "aggregate-payment"
        private const val NULL_SAGA_CONTEXT = "null"
    }

    private val eventRecordTable = "${safeIdentifier(schemaName)}.event_record"

    suspend fun createPayment(paymentId: UUID, orderId: UUID, amount: Int): Boolean {
        val event = PaymentCreatedEvent(paymentId = paymentId, orderId = orderId, amount = amount)
        event.version = 0L
        return try {
            insertEventRecord(paymentId, 0L, PAYMENT_CREATED_EVENT, event.id, objectMapper.writeValueAsString(event))
            true
        } catch (e: Exception) {
            if (isDuplicate(e)) {
                false
            } else {
                throw e
            }
        }
    }

    suspend fun logSubmission(
        paymentId: UUID,
        orderId: UUID,
        transactionId: UUID,
        startedAt: Long,
        spentInQueueDuration: Duration,
    ) {
        val event = PaymentSubmittedEvent(
            paymentId = paymentId,
            success = true,
            orderId = orderId,
            transactionId = transactionId,
            startedAt = startedAt,
            spentInQueueDuration = spentInQueueDuration,
        )
        event.version = 1L
        insertEventRecord(paymentId, 1L, PAYMENT_SUBMITTED_EVENT, event.id, objectMapper.writeValueAsString(event))
    }

    suspend fun logProcessing(
        paymentId: UUID,
        orderId: UUID,
        amount: Int,
        transactionId: UUID,
        success: Boolean,
        submittedAt: Long,
        processedAt: Long,
        spentInQueueDuration: Duration,
        reason: String?,
    ) {
        val event = PaymentProcessedEvent(
            paymentId = paymentId,
            success = success,
            orderId = orderId,
            submittedAt = submittedAt,
            processedAt = processedAt,
            amount = amount,
            transactionId = transactionId,
            reason = reason,
            spentInQueueDuration = spentInQueueDuration,
        )
        event.version = 2L
        insertEventRecord(paymentId, 2L, PAYMENT_PROCESSED_EVENT, event.id, objectMapper.writeValueAsString(event))
    }

    private suspend fun insertEventRecord(
        paymentId: UUID,
        aggregateVersion: Long,
        eventTitle: String,
        eventId: UUID,
        payload: String,
    ) {
        val insertSql = """
            INSERT INTO $eventRecordTable
                (id, aggregate_table_name, aggregate_id, aggregate_version, event_title, payload, saga_context)
            VALUES
                (:id, :aggregateTableName, :aggregateId, :aggregateVersion, :eventTitle, :payload, :sagaContext)
        """.trimIndent()

        val rowsUpdated = databaseClient.sql(insertSql)
            .bind("id", eventId.toString())
            .bind("aggregateTableName", AGGREGATE_TABLE_NAME)
            .bind("aggregateId", paymentId.toString())
            .bind("aggregateVersion", aggregateVersion)
            .bind("eventTitle", eventTitle)
            .bind("payload", payload)
            .bind("sagaContext", NULL_SAGA_CONTEXT)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

        if (rowsUpdated != 1L) {
            logger.warn("Unexpected rowsUpdated={} while inserting payment event {}", rowsUpdated, eventTitle)
        }
    }

    private fun isDuplicate(e: Exception): Boolean {
        return e is DuplicateKeyException ||
            e is R2dbcDataIntegrityViolationException ||
            (e.message?.contains("duplicate key", ignoreCase = true) == true)
    }

    private fun safeIdentifier(raw: String): String {
        require(raw.matches(Regex("[A-Za-z0-9_]+"))) {
            "Illegal schema identifier: $raw"
        }
        return raw
    }
}
