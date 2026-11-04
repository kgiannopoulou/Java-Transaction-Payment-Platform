package com.kgiannopoulou.ledgerforge.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Assigns every request a trace id (or adopts a valid incoming {@code X-Trace-Id}), exposes it in
 * the MDC for logging and echoes it in the response header. Error bodies carry the same value, so
 * a client-reported {@code traceId} leads straight to the matching server log lines.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceIds.fromHeaderOrNew(request.getHeader(TraceIds.HEADER));
        MDC.put(TraceIds.MDC_KEY, traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear(); // the trace id and everything LogContext added during the request
        }
    }
}
