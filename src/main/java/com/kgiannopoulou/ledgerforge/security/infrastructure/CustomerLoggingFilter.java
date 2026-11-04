package com.kgiannopoulou.ledgerforge.security.infrastructure;

import com.kgiannopoulou.ledgerforge.security.application.CurrentPrincipal;
import com.kgiannopoulou.ledgerforge.shared.web.LogContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds the authenticated customer to the log context. Added to the security chain directly, after
 * token validation, so only an id from a verified token can reach the logs. Deliberately not a
 * {@code @Component}: Spring Boot would also register it as a servlet filter, ahead of security.
 */
class CustomerLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CurrentPrincipal.customerId().ifPresent(id -> LogContext.put(LogContext.CUSTOMER_ID, id));
        chain.doFilter(request, response);
    }
}
