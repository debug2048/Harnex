package com.harnex.web.config;

import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.auth.ClientIdentity;
import com.harnex.run.RunRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Existing Runtime clients may observe each other, but cannot steer or resolve another client's active Run. */
@Configuration
public class ClientOwnershipConfig implements WebMvcConfigurer, HandlerInterceptor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RunRegistry runs;
    public ClientOwnershipConfig(RunRegistry runs) { this.runs = runs; }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this).addPathPatterns("/api/v1/**"); }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) return true;
        String route = String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        var variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> values ? values : Map.of();
        boolean forbidden = false;
        if (route.startsWith("/api/v1/runs/") && variables.get("runId") instanceof String runId) {
            var view = runs.view(runId);
            if (view == null) return true;
            var client = ClientIdentity.actor();
            boolean hostCancel = "POST".equals(request.getMethod())
                && "/api/v1/runs/{runId}/cancel".equals(route) && client.hostCanCancel(view);
            forbidden = !client.canControl(view.get("origin")) && !hostCancel;
        } else if ((route.startsWith("/api/v1/conversations/") || route.startsWith("/api/v1/agent/conversations/"))
            && !route.endsWith("/forks")) {
            Object id = variables.get("conversationId");
            if (id == null) id = variables.get("threadId");
            if (id == null) id = variables.get("id");
            forbidden = id instanceof String conversationId
                && !runs.clientCanControlConversation(conversationId, ClientIdentity.actor());
        }
        if (!forbidden) return true;
        response.setStatus(409); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        JSON.writeValue(response.getWriter(), Map.of("error", Map.of("code", "conversation_owned_elsewhere",
            "message", "此会话的任务由另一客户端操作。可以查看历史，请在任务所属客户端继续或结束任务。")));
        return false;
    }
}
