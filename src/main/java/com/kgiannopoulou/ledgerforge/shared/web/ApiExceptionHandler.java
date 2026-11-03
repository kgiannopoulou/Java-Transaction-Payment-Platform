package com.kgiannopoulou.ledgerforge.shared.web;

import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;
import com.kgiannopoulou.ledgerforge.shared.error.ConflictException;
import com.kgiannopoulou.ledgerforge.shared.error.DomainException;
import com.kgiannopoulou.ledgerforge.shared.error.InvalidRequestException;
import com.kgiannopoulou.ledgerforge.shared.error.NotFoundException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Turns every failure into the same error shape (an RFC 9457 problem document):
 *
 * <pre>{@code
 * {
 *   "type":    "INSUFFICIENT_FUNDS",          // stable, machine-readable
 *   "title":   "Insufficient funds",          // short summary of the problem type
 *   "status":  422,
 *   "detail":  "Account 3f0c... has insufficient available funds",
 *   "traceId": "9d1c2f..."                    // same value as the X-Trace-Id response header
 * }
 * }</pre>
 *
 * <p>Domain exceptions map to a status by their sealed category. Framework exceptions (404 route,
 * 405, 415, validation, malformed JSON) are normalised into the same shape. Anything unexpected
 * becomes a generic 500: the stack trace is logged with the trace id, never returned.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException e) {
        HttpStatus status = switch (e) {
            case NotFoundException ignored -> HttpStatus.NOT_FOUND;
            case BusinessRuleViolationException ignored -> HttpStatus.UNPROCESSABLE_CONTENT;
            case ConflictException ignored -> HttpStatus.CONFLICT;
            case InvalidRequestException ignored -> HttpStatus.BAD_REQUEST;
        };
        log.debug("Request rejected: {} - {}", e.type(), e.getMessage());
        return respond(problem(status, e.type(), e.title(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid parameter",
                "Invalid value for parameter '" + e.getName() + "'"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException e) {
        // Normally prevented by explicit checks; reaching this means a race lost to a DB constraint.
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return respond(problem(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION", "Conflicting data",
                "The request conflicts with existing data"));
    }

    /** Denied by {@code @PreAuthorize} ownership checks (method security runs inside MVC). */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException e) {
        return respond(problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied",
                "You are not allowed to perform this operation"));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException e) {
        return respond(problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required",
                "A valid bearer token is required"));
    }

    /** Last line of defence: log everything, reveal nothing. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("Unhandled exception [traceId={}]", TraceIds.current(), e);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error",
                "An unexpected error occurred. Quote the traceId when contacting support."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed",
                "Request validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Malformed request", "Request body is missing or malformed"));
    }

    /** Normalises the problem bodies Spring builds for framework exceptions (404, 405, 415, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body == null && ex instanceof ErrorResponse errorResponse) {
            body = errorResponse.getBody();
        }
        if (body instanceof ProblemDetail problem) {
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            problem.setType(URI.create(status != null ? status.name() : "HTTP_" + statusCode.value()));
            problem.setProperty("traceId", TraceIds.current());
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type));
        problem.setTitle(title);
        problem.setProperty("traceId", TraceIds.current());
        return problem;
    }
}
