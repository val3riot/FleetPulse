package it.fleetpulse.api.common;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";
    private static final String ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".id";
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        String requestId = (String) request.getAttribute(ATTRIBUTE);
        if (requestId == null) {
            requestId = resolveId(request);
            request.setAttribute(ATTRIBUTE, requestId);
        }
        MDC.put("requestId", requestId);
        response.setHeader(HEADER, requestId);
        long started = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            log.atInfo().addKeyValue("event.action",
                    completed ? "http.request.completed" : "http.request.failed")
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("status", completed ? response.getStatus() : 500)
                    .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                    .log(completed ? "HTTP request completed" : "HTTP request failed");
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    private static String resolveId(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders(HEADER));
        if (values.size() == 1) {
            String value = values.getFirst();
            try {
                UUID parsed = UUID.fromString(value);
                if (parsed.toString().equalsIgnoreCase(value)) {
                    return parsed.toString();
                }
            } catch (IllegalArgumentException ignored) {
                // Diagnostic headers must not reject an otherwise valid request.
            }
        }
        return UUID.randomUUID().toString();
    }
}
