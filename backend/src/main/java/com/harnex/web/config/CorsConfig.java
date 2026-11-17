package com.harnex.web.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.harnex.config.AgentConfig;
import com.harnex.preview.PreviewService;

import lombok.extern.slf4j.Slf4j;

/**
 * Centralised CORS policy. Replaces the per-controller {@code @CrossOrigin} (which silently defaulted to {@code "*"})
 * with an explicit allow-list driven by {@code harnex.cors.*}. Set the list to an empty array to disable CORS entirely
 * (recommended when the SPA and API share an origin via nginx in production).
 */
@Slf4j
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer(AgentConfig cfg) {
        AgentConfig.Cors c = cfg.getCors();
        List<String> origins = c.getAllowedOrigins() == null ? List.of() : c.getAllowedOrigins();
        // Browsers refuse `Access-Control-Allow-Credentials: true` together with a wildcard origin
        // (or any pattern that matches "*") — misconfiguring this combination silently breaks every
        // authenticated cross-origin call. Fail fast at start-up so the operator sees the
        // problem before users do.
        if (c.isAllowCredentials()) {
            for (String o : origins) {
                if (o == null)
                    continue;
                String trimmed = o.trim();
                if ("*".equals(trimmed) || trimmed.contains("*") && !trimmed.contains(".")) {
                    throw new IllegalStateException(
                        "CORS misconfigured: harnex.cors.allowCredentials=true cannot be combined with "
                            + "a wildcard allowed-origin '" + trimmed + "'. Either disable credentials "
                            + "or list the exact origins you want to allow.");
                }
            }
        }
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                // Opaque preview documents load only resources protected by their short-lived read grant.
                registry.addMapping(PreviewService.CONTENT_PREFIX + "**")
                    .allowedOrigins("*").allowedMethods("GET", "HEAD").allowedHeaders("*").allowCredentials(false);
                if (origins.isEmpty()) {
                    log.info("CORS disabled (no allowed-origins configured)");
                    return;
                }
                log.info("CORS enabled for /api/** and /actuator/** with origins={}", origins);
                String[] originArr = origins.toArray(new String[0]);
                String[] methods =
                    c.getAllowedMethods() == null ? new String[] {"GET", "POST", "PUT", "DELETE", "OPTIONS"}
                        : c.getAllowedMethods().toArray(new String[0]);
                String[] headers =
                    c.getAllowedHeaders() == null ? new String[] {"*"} : c.getAllowedHeaders().toArray(new String[0]);
                registry.addMapping("/api/**").allowedOriginPatterns(originArr).allowedMethods(methods)
                    .allowedHeaders(headers).exposedHeaders("X-Harnex-Scope-Id", "X-Harnex-Instance-Id")
                    .allowCredentials(c.isAllowCredentials()).maxAge(c.getMaxAgeSeconds());
                registry.addMapping("/actuator/**").allowedOriginPatterns(originArr).allowedMethods("GET")
                    .allowedHeaders(headers).exposedHeaders("X-Harnex-Scope-Id", "X-Harnex-Instance-Id")
                    .allowCredentials(c.isAllowCredentials()).maxAge(c.getMaxAgeSeconds());
            }
        };
    }
}
