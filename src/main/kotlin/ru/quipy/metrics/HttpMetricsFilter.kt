package ru.quipy.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping

@Component
class HttpMetricsFilter(
    private val meterRegistry: MeterRegistry,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startNs = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } finally {
            val method = request.method
            val matchedPattern = (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String)
            val uriForLabel = matchedPattern ?: request.requestURI
            val status = response.status.toString()

            meterRegistry
                .counter(
                    "service_incoming_requests_total",
                    "method",
                    method,
                    "uri",
                    uriForLabel,
                    "status",
                    status,
                ).increment()

            val isPaymentRoute =
                matchedPattern != null &&
                    !matchedPattern.startsWith("/actuator") &&
                    matchedPattern != "/error" &&
                    matchedPattern.contains("/payment")

            if (isPaymentRoute) {
                meterRegistry
                    .counter(
                        "service_payments_incoming_requests_total",
                    ).increment()
            }

            val duration = System.nanoTime() - startNs
            Timer
                .builder("service_http_request_duration_seconds")
                .tags(
                    "method", method,
                    "uri", uriForLabel,
                    "status", status,
                )
                .publishPercentiles(0.9, 0.95, 0.99)
                .register(meterRegistry)
                .record(java.time.Duration.ofNanos(duration))
        }
    }
}
