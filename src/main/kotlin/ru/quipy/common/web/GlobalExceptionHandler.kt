package ru.quipy.common.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import kotlin.math.ceil

class TooManyRequestsException(
    val retryAfterMillis: Long,
    message: String = "Too Many Requests",
) : RuntimeException(message)

@ControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(TooManyRequestsException::class)
    fun handleTooManyRequestsException(ex: TooManyRequestsException): ResponseEntity<String> {
        val seconds = maxOf(1L, ceil(ex.retryAfterMillis / 1000.0).toLong())
        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", seconds.toString())
            .body(ex.message)
    }
}

