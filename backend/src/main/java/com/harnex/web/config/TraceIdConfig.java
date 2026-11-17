package com.harnex.web.config;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Per-request {@code traceId} bound into SLF4J's MDC and echoed back to the caller as {@code X-Harnex-Trace-Id}. Lets
 * operators (and end users via DevTools) correlate a single user-visible failure to the exact backend log lines without
 * grepping by timestamp.
 *
 * <p>
 * The filter accepts an inbound {@code X-Harnex-Trace-Id} so a frontend that already minted one (e.g. via the cockpit's
 * debug overlay) can preserve it across the boundary; otherwise it mints a fresh 32-char hex id from
 * {@link UUID#randomUUID()}.
 *
 * <p>
 * To surface the id in log files add {@code %X{traceId}} to the logback pattern:
 * 
 * <pre>
 *   logging.pattern.console: "%d %-5level [%X{traceId}] %logger{36} - %msg%n"
 * </pre>
 */
@Configuration
public class TraceIdConfig {

    static final String HEADER = "X-Harnex-Trace-Id";
    static final String MDC_KEY = "traceId";

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> reg = new FilterRegistrationBean<>(new TraceIdFilter());
        reg.addUrlPatterns("/api/*");
        // Run BEFORE auth so even rejected requests carry a trace id in their access log line.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    static class TraceIdFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
            String inbound = req.getHeader(HEADER);
            String traceId = (inbound != null && inbound.matches("[A-Za-z0-9_-]{8,64}")) ? inbound
                : UUID.randomUUID().toString().replace("-", "");
            MDC.put(MDC_KEY, traceId);
            res.setHeader(HEADER, traceId);
            try {
                chain.doFilter(req, res);
            } finally {
                MDC.remove(MDC_KEY);
            }
        }
    }
}
