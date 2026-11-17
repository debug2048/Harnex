package com.harnex.gateway.im;

import com.harnex.config.ProjectRuntime;
import com.harnex.conversation.ConversationRepository;
import com.harnex.execution.ExecutionSpec;
import com.harnex.execution.ScopeIdentity;
import com.harnex.gateway.im.ImTypes.Inbound;
import com.harnex.run.*;
import com.harnex.run.RunNotification.Mode;
import com.harnex.run.RunNotification.Recipient;
import com.harnex.safety.SecretRedactor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

import io.swagger.v3.oas.annotations.media.Schema;

/** Shared Agent/Goal/automation notification policy. Only the existing IM outbox talks to platforms. */
@Service
@ProjectRuntime
public final class ImTaskNotifications implements RunNotificationSupport {
    private final ImConfigStore connections;
    private final ImNotificationSettings settings;
    private final ImJournal journal;
    private final RunRegistry runs;
    private final RunLedger ledger;
    private final ConversationRepository archive;
    private final ScopeIdentity scope;
    private final ImWorkspaceContext workspace;
    private final Set<String> watched = ConcurrentHashMap.newKeySet();
    private boolean recovered;
    private static final Set<RunStatus> ATTENTION = Set.of(RunStatus.READY_FOR_REVIEW, RunStatus.BLOCKED, RunStatus.BUDGET_LIMITED);
    private static final Set<RunStatus> FAILURES = Set.of(RunStatus.FAILED, RunStatus.INCOMPLETE, RunStatus.EXPIRED);

    public ImTaskNotifications(ImConfigStore connections, ImNotificationSettings settings, ImJournal journal,
        RunRegistry runs, RunLedger ledger, ConversationRepository archive, ScopeIdentity scope, ImWorkspaceContext workspace) {
        this.connections = connections; this.settings = settings; this.journal = journal; this.runs = runs;
        this.ledger = ledger; this.archive = archive; this.scope = scope; this.workspace = workspace;
    }
    @Schema(name = "NotificationRecipientOption")
    public record Option(Recipient recipient, boolean online, boolean available, String reason) {}
    @Schema(name = "NotificationSettingsView")
    public record SettingsView(String revision, Recipient defaultRecipient, boolean valid, List<Option> recipients) {}

    public SettingsView view(Map<String, Boolean> online) {
        var saved = settings.read();
        List<Option> recipients = new ArrayList<>();
        for (String channel : ImTypes.CHANNELS) {
            var config = connections.get(channel);
            for (var peer : config.peers()) {
                String id = ImTypes.key(channel, config.clientId(), peer.userId(), peer.chatId());
                Inbound recent = journal.latestSource(id);
                String chatType = recent != null ? recent.chatType() : peer.chatType();
                if (chatType == null || !Set.of("group", "single").contains(chatType)) chatType = "unknown";
                boolean available = !"dingtalk".equals(channel) || !"unknown".equals(chatType);
                String name = peer.label() == null || peer.label().isBlank() ? peer.userId() : peer.label();
                Recipient target = new Recipient(id, channel, config.clientId(), peer.userId(), peer.chatId(), name, chatType);
                recipients.add(new Option(target, Boolean.TRUE.equals(online.get(channel)), available,
                    available ? "" : "请先在此会话给机器人发一条消息，以确认私聊或群聊"));
            }
        }
        boolean valid = saved.recipient() != null && recipients.stream().anyMatch(option ->
            option.available() && option.recipient().id().equals(saved.recipient().id()));
        return new SettingsView(saved.revision(), saved.recipient(), valid, List.copyOf(recipients));
    }
    public void select(String revision, String recipientId) {
        Recipient recipient = recipientId == null || recipientId.isBlank() ? null : view(Map.of()).recipients().stream()
            .filter(option -> option.available() && option.recipient().id().equals(recipientId)).map(Option::recipient).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("请选择当前项目已授权的会话"));
        settings.save(revision, recipient);
    }
    @Override public RunNotification capture(Mode mode) {
        if (Mode.orOff(mode) == Mode.OFF) return null;
        SettingsView view = view(Map.of());
        if (!view.valid()) throw new IllegalArgumentException("请先在项目设置 → IM 客户端选择已授权的默认通知会话");
        Recipient current = view.recipients().stream().map(Option::recipient)
            .filter(item -> item.id().equals(view.defaultRecipient().id())).findFirst().orElseThrow();
        return new RunNotification(mode, current);
    }
    @Override public void track(RunHandle run) {
        if (run.getNotification() != null) watched.add(run.getRunId());
    }
    /** Called by the existing delivery timer. Restart scans once; steady state only visits opted-in Runs. */
    public synchronized void reconcile() {
        if (!recovered) {
            for (RunRecord record : ledger.list()) {
                if (record.notification() != null && (!record.status().isTerminal() || !journal.notificationRecorded(record.runId(), eventKey(record)))) watched.add(record.runId());
            }
            recovered = true;
        }
        for (String id : List.copyOf(watched)) {
            RunHandle live = runs.get(id);
            RunStatus status = live == null ? null : live.getStatus();
            if (live != null && !status.isTerminal() && !ATTENTION.contains(status)) continue;
            RunRecord record = live == null ? ledger.find(id).orElse(null) : live.snapshot();
            if (record == null) { watched.remove(id); continue; }
            RunNotification intent = record.notification();
            boolean eligible = intent != null && record.origin().kind() != RunOrigin.Kind.GATEWAY
                && record.executionSpec() != null && record.executionSpec().kind() == ExecutionSpec.Kind.AGENT
                && (FAILURES.contains(record.status()) || intent.mode() == Mode.ALL
                    && (record.status() == RunStatus.COMPLETED || record.goalId() != null && ATTENTION.contains(record.status())));
            String event = eventKey(record);
            if (eligible && !journal.notificationRecorded(id, event)) {
                String message = format(record);
                journal.enqueueNotification(id, event, destination(intent.recipient()),
                    ImGatewayService.chunks(message, "wecom".equals(intent.recipient().channel()) ? 3800 : 1800));
            }
            if (record.status().isTerminal()) watched.remove(id);
        }
    }
    static String eventKey(RunRecord record) {
        // A resumed Goal can block again at a later Step; one reminder per Step/status, never per model call.
        return record.status().name() + (ATTENTION.contains(record.status()) ? ":" + Objects.toString(record.checkpoint().stepId(), "initial") : "");
    }
    boolean currentReminder(ImJournal.Delivery delivery) {
        if (ATTENTION.stream().noneMatch(status -> delivery.notificationEvent.startsWith(status.name() + ":"))) return true;
        RunHandle live = runs.get(delivery.runId);
        RunRecord record = live == null ? ledger.find(delivery.runId).orElse(null) : live.snapshot();
        return record != null && delivery.notificationEvent.equals(eventKey(record));
    }
    private String format(RunRecord record) {
        String input = record.submission() == null ? "" : record.submission().request().input();
        String title = trim(input == null ? "" : input.replaceFirst("(?i)^/goal\\s+", "").split("\\R", 2)[0], 100);
        String kind = record.goalId() != null ? "Goal" : record.origin().kind() == RunOrigin.Kind.AUTOMATION ? "自动化" : "Agent";
        String outcome = switch (record.status()) {
            case COMPLETED -> "已完成"; case READY_FOR_REVIEW -> "等待验收"; case BLOCKED -> "需要你处理";
            case BUDGET_LIMITED -> "达到预算限制"; case EXPIRED -> "运行超时"; case INCOMPLETE -> "未完成"; default -> "运行失败";
        };
        String output = record.lastStepResult() == null ? "" : record.lastStepResult().output();
        if (record.submission() != null) {
            try {
                var history = archive.captureRunHistory(scope.scopeId(), record.threadId(), record.submission().turnId(), record.runId());
                if (history.answer() != null && !history.answer().plainText().isBlank()) output = history.answer().plainText();
            } catch (RuntimeException unavailable) {
                if (output == null || output.isBlank()) output = "暂时无法读取文本结果，请回到项目查看运行记录。";
            }
        }
        String reason = record.error();
        if ((reason == null || reason.isBlank()) && record.goalExecution() != null) reason = record.goalExecution().stopReason();
        String project = scope.projectRoot().getFileName() == null ? scope.projectRoot().toString() : scope.projectRoot().getFileName().toString();
        return kind + " · " + outcome + "\n项目：" + project + " · " + workspace.machine()
            + (title.isBlank() ? "" : "\n任务：" + title)
            + (reason == null || reason.isBlank() ? "" : "\n原因：" + trim(reason, 250))
            + (output == null || output.isBlank() ? "" : "\n\n" + clean(output))
            + (ATTENTION.contains(record.status()) ? "\n\n请回到 Harnex 此项目继续处理。" : "");
    }
    // Keep the full result; the shared outbox splits it within the platform's byte budget after redaction.
    private static String clean(String value) {
        return SecretRedactor.redact(value).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
    }
    private static String trim(String value, int length) {
        String text = clean(value);
        return text.codePointCount(0, text.length()) > length ? text.substring(0, text.offsetByCodePoints(0, length)) + "…" : text;
    }
    static Inbound destination(Recipient recipient) {
        return new Inbound(recipient.channel(), recipient.account(), recipient.userId(), recipient.chatId(), recipient.label(),
            "notification", "", "", 0, "", recipient.chatType());
    }
}
