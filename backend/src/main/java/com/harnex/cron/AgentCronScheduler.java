package com.harnex.cron;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.env.Environment;
import com.harnex.config.*;
import com.harnex.execution.PlanReviewPolicy;
import com.harnex.core.StepLimits;
import com.harnex.execution.ScopeIdentity;
import com.harnex.gateway.im.ImGatewayService;
import com.harnex.gateway.im.ImTaskNotifications;
import com.harnex.run.RunNotification;
import com.harnex.util.Ids;
import com.harnex.cron.AutomationStore.*;
import com.harnex.cron.AutomationRuntimeDirectory.*;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/** User-level plans; execution is dispatched only to an already available Runtime. */
@Slf4j @Component
public class AgentCronScheduler {
    private final AutomationStore store;
    private final AutomationRuntimeDirectory directory;
    private final AutomationExecutionService execution;
    private final AgentConfig config;
    private final ScopeIdentity scope;
    private final Environment environment;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "automation-dispatch"); thread.setDaemon(true); return thread;
    });
    public AgentCronScheduler(AutomationStore store, AutomationRuntimeDirectory directory, AutomationExecutionService execution,
        AgentConfig config, ScopeIdentity scope, Environment environment) {
        this.store = store; this.directory = directory; this.execution = execution; this.config = config; this.scope = scope; this.environment = environment;
    }
    @EventListener(ApplicationReadyEvent.class) public void init() {
        // Embedded test contexts do not execute a user's real schedules. All normal launchers use main().
        if (environment.getProperty("harnex.cron.scheduler-enabled", Boolean.class, Boolean.getBoolean("harnex.hosted"))) {
            timer.scheduleWithFixedDelay(() -> { try { tick(); } catch (Exception failure) { log.warn("Automation dispatch failed: {}", failure.toString()); } }, 2, 3, TimeUnit.SECONDS);
        }
    }
    @PreDestroy public void close() { timer.shutdownNow(); }
    public String upsert(CronJob request) {
        if (request == null || request.getPrompt() == null || request.getPrompt().isBlank()) throw new IllegalArgumentException("Task instructions are required");
        if (request.getPrompt().length() > 1_000_000) throw new IllegalArgumentException("Task instructions are too long");
        Target target = directory.target(request.getTargetId());
        SchedulePreview schedule = preview(request.getCron(), request.getTimeZone());
        if (request.getMaxWorkIterations() != null) StepLimits.resolve(config, request.getMaxWorkIterations(), config.getMaxIterations());
        if (request.getId() == null || request.getId().isBlank()) request.setId(UUID.randomUUID().toString());
        Ids.requireSafe(request.getId(), "automation job");
        CronJob task = configuration(request); task.setTargetId(target.id()); task.setTimeZone(schedule.timeZone());
        if (target.profile() == RuntimeProfile.PERSONAL) {
            if (task.isAllowMutations()) throw new IllegalArgumentException("Personal space has no project file or command tools");
            if (RunNotification.Mode.orOff(task.getNotificationMode()) != RunNotification.Mode.OFF)
                throw new IllegalArgumentException("IM 提醒仅适用于项目任务");
            task.setToolsets(List.of()); task.setMaxWorkIterations(null);
        }
        return store.update(document -> {
            Job previous = document.getJobs().get(task.getId());
            if (previous == null && config.getCron().getMaxJobs() > 0 && document.getJobs().size() >= config.getCron().getMaxJobs()) {
                throw new IllegalArgumentException("Automation task limit reached");
            }
            Job job = previous == null ? new Job() : previous;
            if (previous != null) history(previous.getTask(), task);
            job.setTask(task); job.setTarget(target);
            if (job.getCreatedAt() == 0) job.setCreatedAt(System.currentTimeMillis());
            job.setNextRunAt(task.isDisabled() ? 0 : schedule.nextRuns().get(0));
            job.setPendingAt(0); job.setWaitingReason(null);
            document.getJobs().put(task.getId(), job);
            return task.getId();
        });
    }
    public void setEnabled(String id, boolean enabled) {
        store.update(document -> {
            Job job = require(document, id); job.getTask().setDisabled(!enabled);
            job.setNextRunAt(enabled ? next(job.getTask(), System.currentTimeMillis()) : 0);
            job.setPendingAt(0); job.setWaitingReason(null); return null;
        });
    }
    public boolean cancel(String id) {
        return store.update(document -> {
            Job job = document.getJobs().remove(id); if (job == null) return false;
            if (job.getActiveAttempt() != null) document.getAttempts().get(job.getActiveAttempt()).setCancelRequested(true);
            return true;
        });
    }
    public void cancelExecution(String id) {
        store.update(document -> {
            Job job = require(document, id); job.setPendingAt(0); job.setWaitingReason(null);
            if (job.getActiveAttempt() != null) document.getAttempts().get(job.getActiveAttempt()).setCancelRequested(true);
            else job.setNextRunAt(job.getTask().isDisabled() ? 0 : next(job.getTask(), System.currentTimeMillis()));
            return null;
        });
    }
    public void runNow(String id) {
        store.update(document -> {
            Job job = require(document, id);
            if (job.getActiveAttempt() != null || job.getPendingAt() > 0) throw new JobBusyException();
            job.setPendingAt(System.currentTimeMillis()); job.setWaitingReason(null); return null;
        });
        if (environment.getProperty("harnex.cron.dispatch-immediately", Boolean.class, true))
            timer.execute(() -> { try { tick(); } catch (Exception failure) { log.warn("Manual automation dispatch failed: {}", failure.toString()); } });
    }
    public List<CronJob> list() { return store.read(document -> document.getJobs().values().stream().map(job -> AutomationStore.copy(job.getTask(), CronJob.class)).toList()); }
    public List<TargetView> targets() { return directory.targets(); }
    @Autowired(required = false)
    private ImGatewayService im;
    public ImTaskNotifications.SettingsView notificationSettings(String targetId) {
        Target target = directory.target(targetId);
        if (target.profile() != RuntimeProfile.PROJECT) throw new IllegalArgumentException("IM 提醒仅适用于项目任务");
        Peer peer = directory.find(target);
        return peer.local() ? im.notificationSettings()
            : directory.request(peer, "/api/im/notifications", null, ImTaskNotifications.SettingsView.class);
    }
    public Map<String, List<String>> toolsets() { return config.getToolsets().getPresets(); }
    public List<CronJobView> views() {
        return store.read(document -> document.getJobs().values().stream().sorted(Comparator.comparingLong(Job::getCreatedAt)).map(job -> {
            CronJob task = job.getTask(); boolean online;
            try { directory.find(job.getTarget()); online = true; } catch (RuntimeException unavailable) { online = false; }
            boolean due = job.getPendingAt() > 0 || !task.isDisabled() && job.getNextRunAt() > 0 && job.getNextRunAt() <= System.currentTimeMillis();
            Attempt currentAttempt = document.getAttempts().get(job.getActiveAttempt());
            String state = job.getActiveAttempt() != null ? online && currentAttempt != null && !"WAITING_RESULT".equals(currentAttempt.getStatus()) ? "RUNNING" : "WAITING_RESULT"
                : due ? online ? "WAITING_IDLE" : "WAITING_RUNTIME" : task.isDisabled() ? "PAUSED" : "SCHEDULED";
            return new CronJobView(task.getId(), task.getName(), task.getCron(), task.getPrompt(), task.getProvider(), task.getModel(),
                task.getToolsets(), task.isAllowMutations(), task.getMaxWorkIterations(), task.getPlanReview(), task.getTimeZone(), task.isDisabled(),
                task.getLastRunAt(), task.getLastSkipAt(), task.getLastFailureAt(), task.getLastFailureReason(), task.getRunCount(), task.getSkipCount(),
                task.getFailureCount(), task.getLastRunId(), task.getLastStatus(), job.getNextRunAt(), job.getActiveAttempt() != null,
                document.getAttempts().values().stream().filter(attempt -> Objects.equals(task.getLastRunId(), attempt.getRunId()))
                    .map(Attempt::getConversationId).filter(Objects::nonNull).findFirst().orElse(null),
                null, job.getTarget(), online, state, job.getWaitingReason(), job.getPendingAt() > 0,
                RunNotification.Mode.orOff(task.getNotificationMode()));
        }).toList());
    }
    public List<Attempt> history(String jobId) {
        return store.read(document -> document.getAttempts().values().stream().filter(attempt -> attempt.getJobId().equals(jobId))
            .sorted(Comparator.comparingLong(Attempt::getStartedAt).reversed()).limit(50).toList());
    }
    public AutomationExecutionService.Result result(String attemptId) { return store.result(attemptId); }

    /** Durable claims make multiple app/CLI hosts sharing the user directory safe; no Runtime is launched here. */
    public synchronized void tick() {
        List<Attempt> active = store.read(document -> document.getAttempts().values().stream().filter(Attempt::unfinished).toList());
        for (Attempt attempt : active) observe(attempt);
        // Keep unattended model/tool load bounded to one task per user directory.
        if (store.read(document -> document.getAttempts().values().stream().anyMatch(Attempt::active))) return;
        List<Job> due = store.read(document -> document.getJobs().values().stream()
            .filter(job -> job.getPendingAt() > 0 || !job.getTask().isDisabled() && job.getNextRunAt() > 0 && job.getNextRunAt() <= System.currentTimeMillis())
            .sorted(Comparator.comparingLong(job -> job.getPendingAt() > 0 ? job.getPendingAt() : job.getNextRunAt())).toList());
        for (Job candidate : due) {
            Peer peer;
            try { peer = directory.find(candidate.getTarget()); }
            catch (OfflineException offline) { continue; }
            Attempt attempt = store.update(document -> {
                Job job = document.getJobs().get(candidate.getTask().getId());
                if (job == null || job.getActiveAttempt() != null || document.getAttempts().values().stream().anyMatch(Attempt::active)
                    || job.getPendingAt() == 0 && (job.getTask().isDisabled() || job.getNextRunAt() > System.currentTimeMillis())) return null;
                if (!job.getTarget().equals(candidate.getTarget())) return null;
                Attempt claim = new Attempt(); claim.setId(UUID.randomUUID().toString()); claim.setJobId(job.getTask().getId());
                claim.setName(job.getTask().getName()); claim.setTarget(job.getTarget()); claim.setTask(AutomationStore.copy(job.getTask(), CronJob.class));
                claim.setStartedAt(System.currentTimeMillis()); claim.setTrigger(job.getPendingAt() > 0 ? "MANUAL" : "SCHEDULED");
                claim.setOwnerPid(ProcessHandle.current().pid()); claim.setOwnerStartedAt(processStart(ProcessHandle.current()));
                document.getAttempts().put(claim.getId(), claim); job.setActiveAttempt(claim.getId()); job.setWaitingReason(null);
                return claim;
            });
            if (attempt == null) continue;
            boolean cancelledBeforeDispatch = store.read(document -> {
                Attempt current = document.getAttempts().get(attempt.getId());
                return current == null || current.isCancelRequested();
            });
            if (cancelledBeforeDispatch) { finish(attempt, "CANCELLED", null, null); continue; }
            try {
                CronJob task = attempt.getTask();
                var request = new AutomationExecutionService.Request(attempt.getId(), attempt.getJobId(), attempt.getTarget().profile(), task.getPrompt(),
                    task.getProvider(), task.getModel(), task.getToolsets(), task.isAllowMutations(), task.getMaxWorkIterations(), task.getPlanReview(), task.getNotificationMode());
                var started = peer.local() ? execution.start(request) : directory.request(peer, "/api/v1/automation-executions", request, AutomationExecutionService.Start.class);
                if (started == null) { finish(attempt, "FAILED", "This Runtime does not support automation dispatch; update it before running the task.", null); return; }
                if ("automation_runtime_busy".equals(started.code()) || "runtime_draining".equals(started.code())) {
                    store.update(document -> {
                        Job job = document.getJobs().get(attempt.getJobId());
                        if (job != null && attempt.getId().equals(job.getActiveAttempt())) { job.setActiveAttempt(null); job.setWaitingReason(started.message()); }
                        document.getAttempts().remove(attempt.getId()); return null;
                    });
                    continue;
                }
                if (started.code() != null) { finish(attempt, "FAILED", started.message(), null); return; }
                boolean cancelRequested = store.update(document -> {
                    Attempt current = document.getAttempts().get(attempt.getId());
                    if (current != null && current.active()) { current.setRunId(started.runId()); current.setConversationId(started.conversationId()); current.setStatus("RUNNING"); }
                    return current != null && current.isCancelRequested();
                });
                if (cancelRequested) {
                    if (peer.local()) execution.cancel(attempt.getId());
                    else directory.request(peer, "/api/v1/automation-executions/" + attempt.getId() + "/cancel", Map.of(), Map.class);
                }
            } catch (IllegalArgumentException invalid) { finish(attempt, "FAILED", invalid.getMessage(), null); }
            catch (OfflineException offline) {
                if (!offline.possiblySubmitted) {
                    store.update(document -> {
                        Job job = document.getJobs().get(attempt.getJobId());
                        if (job != null && attempt.getId().equals(job.getActiveAttempt())) {
                            job.setActiveAttempt(null); job.setWaitingReason("Waiting for the selected execution environment");
                        }
                        document.getAttempts().remove(attempt.getId()); return null;
                    });
                } else store.update(document -> {
                    Attempt current = document.getAttempts().get(attempt.getId());
                    if (current != null) current.setError("Submission outcome is unknown; waiting for this environment to reconnect.");
                    return null;
                });
            }
            catch (Exception uncertain) {
                // A transport error may follow acceptance. Inspect the same durable request; never POST it again.
                store.update(document -> { Attempt current = document.getAttempts().get(attempt.getId()); if (current != null) current.setError("Submission outcome is unknown; waiting for this environment to reconnect."); return null; });
            }
            return;
        }
    }
    private void observe(Attempt attempt) {
        try {
            Peer peer = directory.find(attempt.getTarget());
            var result = peer.local() ? execution.inspect(attempt.getId())
                : directory.request(peer, "/api/v1/automation-executions/" + attempt.getId(), null, AutomationExecutionService.Result.class);
            if (result == null) {
                boolean ownerAlive = ProcessHandle.of(attempt.getOwnerPid()).filter(ProcessHandle::isAlive)
                    .map(owner -> processStart(owner) == attempt.getOwnerStartedAt()).orElse(false);
                if (attempt.getError() != null || !ownerAlive || System.currentTimeMillis() - attempt.getStartedAt() > 30_000) {
                    finish(attempt, "INTERRUPTED", "No accepted execution could be recovered. Check the environment before running again.", null);
                }
                return;
            }
            if (result.terminal()) { store.saveResult(attempt.getId(), result); finish(attempt, result.status(), result.error(), result); return; }
            if ("WAITING_RESULT".equals(attempt.getStatus())) store.update(document -> {
                Attempt current = document.getAttempts().get(attempt.getId());
                if (current != null && current.unfinished()) current.setStatus("RUNNING");
                return null;
            });
            if (attempt.isCancelRequested()) {
                if (peer.local()) execution.cancel(attempt.getId());
                else directory.request(peer, "/api/v1/automation-executions/" + attempt.getId() + "/cancel", Map.of(), Map.class);
            }
        } catch (OfflineException offline) {
            // Preserve this job's claim without making a closed project block personal tasks indefinitely.
            store.update(document -> {
                Attempt current = document.getAttempts().get(attempt.getId());
                if (current != null && current.unfinished()) current.setStatus("WAITING_RESULT");
                return null;
            });
        }
    }
    private void finish(Attempt attempt, String status, String error, AutomationExecutionService.Result result) {
        store.update(document -> {
            Attempt current = document.getAttempts().get(attempt.getId());
            if (current == null || !current.unfinished()) return null;
            current.setStatus(status); current.setError(error); current.setFinishedAt(result != null && result.finishedAt() > 0 ? result.finishedAt() : System.currentTimeMillis());
            current.setTask(null); // The source Run retains the accepted input; don't duplicate large prompts in the global index.
            if (result != null) { current.setRunId(result.runId()); current.setConversationId(result.conversationId()); }
            Job job = document.getJobs().get(attempt.getJobId());
            if (job != null && attempt.getId().equals(job.getActiveAttempt())) {
                job.setActiveAttempt(null); job.setPendingAt(0); job.setWaitingReason(null);
                CronJob task = job.getTask(); task.setLastRunAt(current.getFinishedAt()); task.setLastRunId(current.getRunId());
                task.setLastStatus(status); task.setRunCount(task.getRunCount() + 1);
                task.setLastFailureReason("COMPLETED".equals(status) || "CANCELLED".equals(status) ? null : error == null ? status : error);
                if (task.getLastFailureReason() != null) { task.setLastFailureAt(current.getFinishedAt()); task.setFailureCount(task.getFailureCount() + 1); }
                job.setNextRunAt(task.isDisabled() ? 0 : next(task, System.currentTimeMillis()));
            }
            return null;
        });
    }
    private static long processStart(ProcessHandle handle) { return handle.info().startInstant().map(Instant::toEpochMilli).orElse(0L); }
    private static Job require(Document document, String id) { Job job = document.getJobs().get(id); if (job == null) throw new NoSuchElementException("Automation no longer exists"); return job; }
    private static long next(CronJob task, long after) {
        ZonedDateTime time = CronExpression.parse(task.getCron()).next(Instant.ofEpochMilli(after).atZone(zone(task.getTimeZone())));
        return time == null ? 0 : time.toInstant().toEpochMilli();
    }
    public static SchedulePreview preview(String expression, String timeZone) {
        CronExpression cron = CronExpression.parse(expression == null ? "" : expression);
        ZoneId zone = zone(timeZone); ZonedDateTime time = ZonedDateTime.now(zone); List<Long> dates = new ArrayList<>();
        for (int i = 0; i < 3; i++) { time = cron.next(time); if (time == null) break; dates.add(time.toInstant().toEpochMilli()); }
        if (dates.isEmpty()) throw new IllegalArgumentException("Schedule has no future occurrence");
        return new SchedulePreview(zone.getId(), dates);
    }
    private static ZoneId zone(String name) {
        try { return name == null || name.isBlank() ? ZoneId.systemDefault() : ZoneId.of(name); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid time zone: " + name); }
    }
    private static CronJob configuration(CronJob input) {
        CronJob task = new CronJob(); task.setId(input.getId()); task.setName(input.getName()); task.setCron(input.getCron()); task.setPrompt(input.getPrompt());
        task.setProvider(input.getProvider()); task.setModel(input.getModel()); task.setToolsets(input.getToolsets() == null ? List.of() : List.copyOf(input.getToolsets()));
        task.setAllowMutations(input.isAllowMutations()); task.setDisabled(input.isDisabled()); task.setMaxWorkIterations(input.getMaxWorkIterations());
        task.setNotificationMode(RunNotification.Mode.orOff(input.getNotificationMode()));
        task.setPlanReview(input.getPlanReview() == null ? PlanReviewPolicy.AUTOMATIC : input.getPlanReview());
        return task;
    }
    private static void history(CronJob previous, CronJob task) {
        task.setLastRunAt(previous.getLastRunAt()); task.setLastSkipAt(previous.getLastSkipAt()); task.setLastFailureAt(previous.getLastFailureAt());
        task.setLastFailureReason(previous.getLastFailureReason()); task.setRunCount(previous.getRunCount()); task.setSkipCount(previous.getSkipCount());
        task.setFailureCount(previous.getFailureCount()); task.setLastRunId(previous.getLastRunId()); task.setLastStatus(previous.getLastStatus());
    }
    public static final class JobBusyException extends RuntimeException { public JobBusyException() { super("This task is already waiting or running"); } }
    public record SchedulePreview(String timeZone, List<Long> nextRuns) {}
    public record CronJobView(String id, String name, String cron, String prompt, String provider, String model,
        List<String> toolsets, boolean allowMutations, Integer maxWorkIterations, PlanReviewPolicy planReview, String timeZone, boolean disabled,
        long lastRunAt, long lastSkipAt, long lastFailureAt, String lastFailureReason, long runCount, long skipCount, long failureCount,
        String lastRunId, String lastStatus, long nextRunAt, boolean running, String conversationId, String persistenceError,
        Target target, boolean targetOnline, String state, String waitingReason, boolean pending,
        RunNotification.Mode notificationMode) {}
    @Data public static class CronJob {
        private String id;
        private String name;
        private String targetId = "personal";
        private RunNotification.Mode notificationMode = RunNotification.Mode.OFF;
        private String cron;
        private String prompt;
        private String provider;
        private String model;
        private List<String> toolsets = new ArrayList<>();
        private boolean allowMutations;
        private Integer maxWorkIterations;
        private PlanReviewPolicy planReview = PlanReviewPolicy.AUTOMATIC;
        private String timeZone;
        private boolean disabled;
        private long lastRunAt;
        private long lastSkipAt;
        private long lastFailureAt;
        private String lastFailureReason;
        private long runCount;
        private long skipCount;
        private long failureCount;
        private String lastRunId;
        private String lastStatus;
    }
}
