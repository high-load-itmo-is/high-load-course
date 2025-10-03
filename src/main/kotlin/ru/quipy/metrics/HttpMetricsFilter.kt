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
    private val meterRegistry: MeterRegistry
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        try {
            filterChain.doFilter(request, response)
        } finally {
            val method = request.method
            val matchedPattern = (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String)
            val uriForLabel = matchedPattern ?: request.requestURI
            val status = response.status.toString()

            // Count all incoming requests (general)
            meterRegistry.counter(
                "service_incoming_requests_total",
                "method", method,
                "uri", uriForLabel,
                "status", status
            ).increment()

            // Count only business incoming requests: mapped app routes excluding actuator and error paths
            val isBusiness = matchedPattern != null &&
                    !matchedPattern.startsWith("/actuator") &&
                    matchedPattern != "/error"

            if (isBusiness) {
                meterRegistry.counter(
                    "service_business_incoming_requests_total",
                    "method", method,
                    "uri", matchedPattern,
                    "status", status
                ).increment()
            }
        }
    }
}
