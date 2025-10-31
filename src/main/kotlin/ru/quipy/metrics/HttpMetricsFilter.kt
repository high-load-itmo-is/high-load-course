package ru.quipy.metrics

import io.micrometer.core.instrument.MeterRegistry
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
        }
    }
}
