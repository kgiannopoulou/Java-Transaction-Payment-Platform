package com.kgiannopoulou.ledgerforge.shared.web;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/** Per-request trace id, kept in the SLF4J MDC so every log line and error response carries it. */
public final class TraceIds {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private TraceIds() {
    }

    /** Accepts a well-formed id propagated by the caller, otherwise generates a new one. */
    static String fromHeaderOrNew(@Nullable String header) {
        return header != null && VALID.matcher(header).matches() ? header : generate();
    }

    static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** The current request's trace id, or a fresh one outside a request (e.g. scheduled jobs). */
    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : generate();
    }
}
