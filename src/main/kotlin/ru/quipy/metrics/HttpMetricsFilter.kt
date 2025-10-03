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
            val pattern = (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String)
                ?: request.requestURI
            val status = response.status.toString()

            meterRegistry.counter(
                "service_incoming_requests_total",
                "method", method,
                "uri", pattern,
                "status", status
            ).increment()
        }
    }
}

