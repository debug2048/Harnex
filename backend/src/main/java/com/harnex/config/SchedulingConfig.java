package com.harnex.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulingConfig {

    @Bean
    @ProjectRuntime
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);
        s.setThreadNamePrefix("harnex-cron-");
        s.setRemoveOnCancelPolicy(true);
        s.initialize();
        return s;
    }

    /**
     * Bounded executor for {@code @Async("chatExec")} chat dispatch. Without this Spring's {@code @EnableAsync} default
     * would resolve to a {@code SimpleAsyncTaskExecutor} that spawns an unbounded number of threads — in a pathological
     * client loop that's a slow OOM. Pool size = 8 + queue 32 mirrors the per-thread parallel-tool pool sizing.
     */
    @Bean(name = "chatExec")
    public TaskExecutor chatExec() {
        ThreadPoolTaskExecutor t = new ThreadPoolTaskExecutor();
        t.setCorePoolSize(4);
        t.setMaxPoolSize(8);
        t.setQueueCapacity(32);
        t.setThreadNamePrefix("harnex-chat-");
        // AbortPolicy: when the pool + queue are both full, fail FAST with a structured
        // RejectedExecutionException that ChatController turns into a "busy" SSE error event
        // and a clean 503-style emitter close. The previous CallerRunsPolicy ran the rejected
        // dispatch synchronously on the servlet (Tomcat) thread — a multi-minute agent loop
        // would then occupy that request thread for the duration, eventually starving Tomcat's
        // worker pool and turning a localised pool-saturation into a global serving outage.
        t.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        t.initialize();
        return t;
    }
}