package com.harnex.web.filter;

import java.io.IOException;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.harnex.config.RuntimeConfiguration;

/** Each authenticated API operation sees one coherent settings snapshot. */
@Configuration
public class RuntimeConfigurationFilter {
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> runtimeConfigurationScope(RuntimeConfiguration runtime) {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
                try (var configuration = runtime.acquire()) { chain.doFilter(request, response); }
            }
        });
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return registration;
    }
}
