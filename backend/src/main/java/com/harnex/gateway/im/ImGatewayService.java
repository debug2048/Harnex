package com.harnex.gateway.im;

import com.harnex.execution.PlanReviewPolicy;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.*;
import com.harnex.execution.TextTarget;
import com.harnex.gateway.GatewayDispatcher;
import com.harnex.gateway.im.ImTypes.*;
import com.harnex.interaction.PendingInteraction;
import com.harnex.model.StreamEvent;
import com.harnex.provider.ProviderException;
import com.harnex.run.RunCoordinator;
import com.harnex.audit.InteractionAuditService;
import com.harnex.tool.approval.ApprovalService;
import com.harnex.tool.approval.UserPromptService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/** IM control plane: admission, source-bound interactions and durable replies share the existing Agent Run. */
@Service
@ProjectRuntime
@Slf4j
public class ImGatewayService {
    private final ImConfigStore settings;
    private final ImJournal journal;
    private final ImTransportFactory factory;
    private final GatewayDispatcher dispatcher;
    private final RunCoordinator runs;
    private final AgentConfig cfg;
    private final AgentInteractionSource interactionSource;
    private final ApprovalService approvals;
    private final UserPromptService prompts;
    private final PlanReviewService plans;
    private final InteractionAuditService audit;
    private final ImModelCommands models;
    private final ImWorkspaceContext workspace;
    private final ImConversations conversations;
    @Autowired(required = false)
    private ImTaskNotifications taskNotifications;
    private final Map<String, Transport> transports = new ConcurrentHashMap<>();
    private final Map<String, Object> generations = new ConcurrentHashMap<>();
    private final Map<String, Active> active = new ConcurrentHashMap<>();
    private final Map<String, PairRequest> pairing = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, String> progress = new ConcurrentHashMap<>();
    private final Map<String, Long> nextProgressAt = new ConcurrentHashMap<>();
    private final ExecutorService commands = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(128), r -> new Thread(r, "im-commands"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService deliveries = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "im-deliveries"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closed;
    private final AtomicBoolean delivering = new AtomicBoolean();
    private volatile String deliveryError = "";

    record Active(String receiptId, Inbound source, String threadId, String runId, long startedAt, TextTarget target, String permissionMode) {}
    record PairRequest(String id, Inbound source, long createdAt) {}
    static final class Pending {
        final String code, peerKey, threadId, runId, callId, kind;
        final Map<String, Object> payload;
        final long expiresAt;
        final Inbound source;
        volatile ImInteractionCard card;
        Pending(Inbound source, Active task, String callId, Map<String, Object> payload, ImInteractionCard card) {
            this.source = source; this.peerKey = source.peerKey(); this.threadId = task.threadId; this.runId = task.runId;
            this.callId = callId; this.payload = payload; this.card = card; this.code = card.code(); this.kind = card.kind(); this.expiresAt = card.expiresAt();
        }
    }

    public ImGatewayService(ImConfigStore settings, ImJournal journal, ImTransportFactory factory,
        GatewayDispatcher dispatcher, RunCoordinator runs, AgentConfig cfg, AgentInteractionSource interactionSource,
        ApprovalService approvals, UserPromptService prompts, PlanReviewService plans, InteractionAuditService audit, ImModelCommands models,
        ImWorkspaceContext workspace, ImConversations conversations) {
        this.settings = settings; this.journal = journal; this.factory = factory; this.dispatcher = dispatcher;
        this.runs = runs; this.cfg = cfg; this.interactionSource = interactionSource; this.approvals = approvals;
        this.prompts = prompts; this.plans = plans; this.audit = audit; this.models = models;
        this.workspace = workspace;
        this.conversations = conversations;
    }
    @PostConstruct public void start() {
        ImTypes.CHANNELS.forEach(this::connect);
        deliveries.scheduleWithFixedDelay(this::deliver, 0, 1, TimeUnit.SECONDS);
    }
    private synchronized void connect(String channel) {
        Object generation = new Object(); generations.put(channel, generation);
        Transport old = transports.remove(channel);
        if (old != null) old.close();
        Connection config = settings.get(channel);
        if (closed || !config.enabled()) return;
        Transport transport = factory.create(channel, config, message -> {
            if (!closed && generations.get(channel) == generation) receive(message);
        });
        transport.onCardAction(action -> !closed && generations.get(channel) == generation
            ? cardAction(action) : new CardResult(false, "连接已变化，请刷新状态后重试", null));
        transports.put(channel, transport); transport.start();
    }
    public synchronized Map<String, Object> save(String channel, String revision, Connection value) {
        return saveConnection(channel, revision, value, false);
    }
    private Map<String, Object> saveConnection(String channel, String revision, Connection value, boolean reusedCredential) {
        value = ImConfigStore.normalize(channel, value);
        Connection previous = settings.get(channel);
        boolean transportChange = previous.enabled() != value.enabled() || !previous.clientId().equals(value.clientId())
            || !previous.cardTemplateId().equals(value.cardTemplateId())
            || (!ImTypes.MASK.equals(value.clientSecret()) && !previous.clientSecret().equals(value.clientSecret()));
        boolean authorityChange = previous.allowMutations() != value.allowMutations() || previous.allowYolo() != value.allowYolo() || !previous.peers().equals(value.peers());
        if ((transportChange || authorityChange) && active.values().stream().anyMatch(a -> a.source.channel().equals(channel)))
            throw new IllegalStateException("该渠道仍有任务运行，请先在原会话发送 /取消，等任务结束后修改设置");
        settings.save(channel, revision, value, reusedCredential);
        Transport current = transports.get(channel);
        boolean retry = value.enabled() && (current == null
            || Set.of("ERROR", "DISCONNECTED").contains(String.valueOf(current.status().get("state"))));
        if (transportChange || retry) {
            pairing.values().removeIf(p -> p.source.channel().equals(channel));
            connect(channel);
        }
        return view();
    }
    public synchronized Map<String, Object> save(String channel, String revision, Connection value, String credentialId, String credentialRevision) {
        return saveConnection(channel, revision, settings.useCredential(channel, value, credentialId, credentialRevision),
            credentialId != null && !credentialId.isBlank());
    }
    public String revealSavedSecret(String id, String revision) { return settings.revealSavedSecret(id, revision); }
    public synchronized Map<String, Object> reconnect(String channel) { ImConfigStore.checkChannel(channel); connect(channel); return view(); }
    public ImTaskNotifications.SettingsView notificationSettings() {
        Map<String, Boolean> online = new LinkedHashMap<>();
        transports.forEach((channel, transport) -> online.put(channel, "CONNECTED".equals(transport.status().get("state"))));
        return taskNotifications.view(online);
    }
    public ImTaskNotifications.SettingsView notificationSettings(String revision, String recipientId) {
        taskNotifications.select(revision, recipientId); return notificationSettings();
    }
    public String revealSecret(String channel, String revision, String clientId) { return settings.revealSecret(channel, revision, clientId); }
    public synchronized Map<String, Object> approvePair(String id, String revision) {
        PairRequest request = pairing.get(id);
        if (request == null || request.createdAt < System.currentTimeMillis() - 600_000)
            throw new IllegalArgumentException("绑定请求已过期，请重新发送 /绑定");
        Connection config = settings.get(request.source.channel());
        if (!config.enabled() || !config.clientId().equals(request.source.account()))
            throw new IllegalArgumentException("机器人已变化，请重新绑定");
        List<Peer> peers = new ArrayList<>(config.peers());
        peers.removeIf(p -> p.userId().equals(request.source.userId()) && p.chatId().equals(request.source.chatId()));
        peers.add(new Peer(request.source.userId(), request.source.chatId(), request.source.userName(), request.source.chatType()));
        settings.save(request.source.channel(), revision,
            new Connection(true, config.clientId(), config.clientSecret(), config.allowMutations(), peers, config.cardTemplateId(), config.allowYolo()));
        pairing.remove(id);
        workspace.visit(request.source);
        enqueue(request.source, "授权成功。请重新发送需要处理的任务，以后直接发消息即可。\n" + help(), 0);
        return view();
    }
    public Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>(settings.view());
        Map<String, Object> statuses = new LinkedHashMap<>();
        ImTypes.CHANNELS.forEach(channel -> {
            Transport connection = transports.get(channel);
            statuses.put(channel, connection == null ? Map.of("state", "DISCONNECTED", "detail", "", "lastMessageAt", 0) : connection.status());
        });
        out.put("statuses", statuses);
        out.put("workspace", cfg.getWorkspace().getRoot());
        out.put("machine", workspace.machine());
        out.put("pairing", pairing.values().stream().filter(p -> p.createdAt >= System.currentTimeMillis() - 600_000)
            .map(p -> Map.of("id", p.id, "code", p.id.substring(0, 6).toUpperCase(Locale.ROOT), "channel", p.source.channel(), "userId", p.source.userId(),
                "chatId", p.source.chatId(), "label", p.source.userName(), "createdAt", p.createdAt)).toList());
        out.put("deliveries", journal.deliveryViews());
        out.put("deliveryError", deliveryError);
        out.put("activeTasks", active.values().stream().map(a -> Map.of("channel", a.source.channel(),
            "userId", a.source.userId(), "chatId", a.source.chatId(), "conversationId", a.threadId, "runId", a.runId,
            "model", a.target == null ? "" : ImModelCommands.label(a.target), "permissionMode", a.permissionMode,
            "progress", progress.getOrDefault(a.source.peerKey(), "正在处理"))).toList());
        return out;
    }

    /** Called on a platform connection: persist before its ACK, then leave the socket thread immediately. */
    void receive(Inbound input) {
        if (closed || !settings.get(input.channel()).enabled()
            || !settings.get(input.channel()).clientId().equals(input.account())) return;
        ImJournal.Receipt receipt = journal.accept(input);
        if (receipt == null) return;
        try { commands.execute(() -> process(receipt)); }
        catch (RejectedExecutionException busy) {
            finish(receipt, "当前消息较多，请稍后再发。此消息未执行。");
        }
    }
    private void process(ImJournal.Receipt receipt) {
        Inbound source = receipt.source;
        try {
            Connection config = settings.get(source.channel());
            if (closed || !config.enabled() || !config.clientId().equals(source.account())) {
                journal.complete(receipt.id, "连接已关闭，此消息未执行。"); return;
            }
            if (!config.permits(source)) {
                if (source.text().isBlank()) { finish(receipt, "请发送文字消息，首次使用会自动申请会话授权。"); return; }
                pairing.values().removeIf(p -> p.createdAt < System.currentTimeMillis() - 600_000);
                if (pairing.size() >= 100 && !pairing.containsKey(source.peerKey())) {
                    finish(receipt, "待授权请求较多，请稍后重试。"); return;
                }
                pairing.put(source.peerKey(), new PairRequest(source.peerKey(), source, System.currentTimeMillis()));
                finish(receipt, "此会话尚未授权，已自动提交申请。确认码：" + source.peerKey().substring(0, 6).toUpperCase(Locale.ROOT)
                    + "。\n请在 10 分钟内到 Harnex 当前工作区的「设置 → IM 客户端」核对用户、会话和确认码，点击确认授权。"
                    + "本条消息尚未执行；确认后请重新发送任务，以后直接发消息即可。");
                return;
            }
            String text = source.text().trim();
            if (text.isEmpty()) {
                finish(receipt, "当前支持文字任务。请把图片或文件放入 Harnex 工作区，并用文字说明需要处理的内容。"); return;
            }
            if (text.length() > 32_000) { finish(receipt, "消息过长，请缩短到 32000 字符以内。"); return; }
            String transition = workspace.visit(source);
            if (!transition.isEmpty()) {
                if (!text.startsWith("/")) {
                    finish(receipt, transition + "\n本条任务尚未执行。请核对目标工作区后重新发送任务；/工作区 可查看项目路径。"); return;
                }
                enqueue(source, transition, 0);
            }
            if (text.startsWith("/")) { command(receipt, text); return; }
            if (directAnswer(receipt, text)) return;
            if (active.containsKey(source.peerKey())) {
                finish(receipt, "上一项任务仍在执行。发送 /状态 查看，或 /取消 停止；此消息未排队。需要回答问题时，请按提示带上编号。");
                return;
            }
            // The command executor serialises IM admissions. Keep the limit at this adapter boundary;
            // RunActivity also covers a timed-out/cancelled worker which has not actually exited yet.
            if (!active.isEmpty() || runs.activeRunCount() > 0) {
                finish(receipt, "当前工作区已有任务在运行。IM 每次只接收一项任务，请结束后重新发送；本条消息未执行、未排队。"); return;
            }
            AgentExecutionOptions options = new AgentExecutionOptions(new PermissionContext(config.allowMutations()),
                StepLimits.resolve(cfg, null, cfg.getMaxIterations()), AgentExecutionOptions.IM, PlanReviewPolicy.AUTOMATIC);
            TextTarget target = models.target(source);
            String permissionMode = models.permissionMode(source);
            dispatcher.dispatchAuthenticated(source.channel(), source.userId(), conversations.routingKey(source),
                source.messageId(), text, 0, (thread, reply, duration) -> {
                    try { finish(receipt, finalReply(reply)); }
                    finally {
                        active.computeIfPresent(source.peerKey(), (key, task) -> task.receiptId.equals(receipt.id) ? null : task);
                        progress.remove(source.peerKey());
                        nextProgressAt.remove(source.peerKey());
                        pending.values().stream().filter(p -> p.peerKey.equals(source.peerKey()) && p.threadId.equals(thread))
                            .forEach(p -> closeInteraction(p, reply != null && reply.startsWith("[agent cancelled]") ? "CANCELLED" : "CLOSED"));
                    }
                }, options, (thread, runId) -> {
                    journal.started(receipt.id, thread, runId);
                    conversations.started(source, thread);
                    active.put(source.peerKey(), new Active(receipt.id, source, thread, runId, System.currentTimeMillis(), target, permissionMode));
                    nextProgressAt.put(source.peerKey(), System.currentTimeMillis() + 45_000);
                    enqueueProgress(source, runId, "已开始处理。会话：" + conversations.current(source) + "\n模型：" + ImModelCommands.label(target) + " · 强度 "
                        + (target.reasoningEffort() == null ? "auto" : target.reasoningEffort()) + " · 模式 " + permissionMode
                        + "。\n完成后会发回结果；需要你确认时会发送卡片。/取消 可停止任务。");
                }, event -> event(source, event), target, permissionMode);
        } catch (Exception e) {
            log.warn("IM dispatch failed (receipt={}, type={}, location={})", receipt.id,
                e.getClass().getSimpleName(), e.getStackTrace().length == 0 ? "unknown" : e.getStackTrace()[0]);
            active.computeIfPresent(source.peerKey(), (key, task) -> task.receiptId.equals(receipt.id) ? null : task);
            finish(receipt, e instanceof ProviderException provider && provider.code.startsWith("text_")
                ? "当前模型或强度配置不可用。请发送 /模型 选择其他已启用模型，或 /强度 auto 恢复默认强度；也可在 Harnex「设置 → 模型」配置模型。设置完成后重新发送任务。"
                : "任务未能正常完成，请在 Harnex 中查看会话详情。未自动重新执行。");
        }
    }
    private void command(ImJournal.Receipt receipt, String text) {
        Inbound source = receipt.source;
        String[] parts = text.split("\\s+", 3);
        String command = parts[0].toLowerCase(Locale.ROOT);
        switch (command) {
            case "/新建", "/new", "/reset", "/继续", "/resume" -> {
                if (active.containsKey(source.peerKey())) {
                    finish(receipt, "当前任务仍在执行。请等它完成，或发送 /取消 并等任务停止后再切换会话；原任务没有被中断。"); return;
                }
                String argument = text.substring(parts[0].length()).trim();
                if (Set.of("/新建", "/new", "/reset").contains(command)) {
                    finish(receipt, argument.isEmpty() ? conversations.fresh(source) : "请先单独发送 /新建，再发送任务内容；这条消息没有执行任务。");
                } else finish(receipt, conversations.resume(source, argument));
            }
            case "/会话", "/sessions" -> finish(receipt, conversations.list(source, text.substring(parts[0].length()).trim()));
            case "/工作区", "/workspace" -> finish(receipt, workspace.description());
            case "/帮助", "/help" -> finish(receipt, help());
            case "/绑定", "/pair" -> finish(receipt, "当前会话已授权，直接发送任务即可；重复绑定不会创建新会话或清空历史。\n" + help());
            case "/模型", "/切换模型", "/model", "/models", "/强度", "/effort", "/模式", "/mode" -> {
                String argument = text.substring(parts[0].length()).trim();
                String reply = Set.of("/强度", "/effort").contains(command) ? models.effort(source, argument)
                    : Set.of("/模式", "/mode").contains(command) ? models.mode(source, argument) : models.execute(source, argument);
                if (active.containsKey(source.peerKey())) reply += "\n当前任务继续使用原设置；如需停止，请发送 /取消。";
                finish(receipt, reply);
            }
            case "/状态", "/status" -> {
                Active task = active.get(source.peerKey());
                if (task != null) nextProgressAt.put(source.peerKey(), System.currentTimeMillis() + 180_000);
                if (task == null) { finish(receipt, "当前会话：" + conversations.current(source) + "\n当前没有运行中的任务。\n下次任务：" + models.selection(source)
                    + " · 模式 " + models.permissionMode(source) + "\n发送 /结果 查看上一项结果，/模型 切换模型。"); return; }
                List<Pending> waiting = pending.values().stream().filter(p -> p.peerKey.equals(source.peerKey()) && live(p)).toList();
                finish(receipt, waiting.isEmpty() ? progress.getOrDefault(source.peerKey(), "任务正在执行")
                    + "（已运行 " + Math.max(0, (System.currentTimeMillis() - task.startedAt) / 1000) + " 秒）。发送 /取消 可停止。"
                    : String.join("\n\n", waiting.stream().map(this::render).toList()));
            }
            case "/取消", "/cancel" -> {
                Active task = active.get(source.peerKey());
                boolean cancelled = task != null && runs.cancel(task.runId);
                if (cancelled) progress.put(source.peerKey(), "正在停止任务");
                finish(receipt, cancelled ? "已请求取消，正在停止。已完成的操作不会自动撤销。" : "当前没有可取消的任务。");
            }
            case "/结果", "/result" -> {
                ImJournal.Receipt last = conversations.latest(source);
                finish(receipt, last == null ? "尚无任务结果。" : Set.of("ACCEPTED", "RUNNING").contains(last.state)
                    ? "任务仍在执行，请发送 /状态 查看。" : last.reply);
            }
            case "/同意", "/approve", "/拒绝", "/deny", "/回答", "/answer" -> resolve(receipt, command, parts);
            case "/详情", "/details" -> {
                Pending item = parts.length > 1 ? pending.get(parts[1].toUpperCase(Locale.ROOT)) : null;
                finish(receipt, item != null && item.peerKey.equals(source.peerKey()) ? item.card.details()
                    : "请带上请求编号，例如 /详情 A1B2C3D4。只能查看你在本会话中的请求。");
            }
            default -> finish(receipt, "未识别这个命令。\n" + help());
        }
    }
    private void resolve(ImJournal.Receipt receipt, String command, String[] parts) {
        Inbound source = receipt.source;
        if (parts.length < 2) { finish(receipt, "请带上提示中的编号，例如 /同意 A1B2C3；可用 /状态 查看。没有操作被批准。"); return; }
        Pending item = pending.get(parts[1].toUpperCase(Locale.ROOT));
        if (item == null || !item.peerKey.equals(source.peerKey())) {
            finish(receipt, "该交互已失效或不属于你在本会话中的任务。请发送 /状态 查看，没有执行此操作。"); return;
        }
        String action = Set.of("/同意", "/approve").contains(command) ? "approve"
            : Set.of("/回答", "/answer").contains(command) ? "answer" : "deny";
        finish(receipt, resolveInteraction(source, item, action, parts.length > 2 ? parts[2] : "").message());
    }
    private boolean directAnswer(ImJournal.Receipt receipt, String text) {
        List<Pending> waiting = pending.values().stream().filter(p -> p.peerKey.equals(receipt.source.peerKey()) && live(p)).toList();
        if (waiting.size() == 1 && "question".equals(waiting.get(0).kind)) {
            finish(receipt, resolveInteraction(receipt.source, waiting.get(0), "answer", text).message()); return true;
        }
        if (waiting.size() > 1 && waiting.stream().anyMatch(p -> "question".equals(p.kind))) {
            finish(receipt, "当前有多个待处理请求，请点击对应卡片，或用 /回答 编号 内容 指定问题。你的消息没有作为新任务执行。"); return true;
        }
        if (!waiting.isEmpty() && Set.of("同意", "批准", "拒绝", "可以", "好的").contains(text)) {
            finish(receipt, "请点击对应卡片的按钮，或回复 /同意 编号、/拒绝 编号。单独一句“同意”不会批准任何操作。"); return true;
        }
        if (!active.containsKey(receipt.source.peerKey()) && ImAnswerParser.looksLikeChoice(text)
            && pending.values().stream().anyMatch(p -> p.peerKey.equals(receipt.source.peerKey()) && "question".equals(p.kind)
                && !p.card.pending() && System.currentTimeMillis() < p.expiresAt + 300_000)) {
            finish(receipt, "上一个问题已经结束，未再次提交答案。请发送完整的新任务，或用 /结果 查看上一项结果。"); return true;
        }
        return false;
    }
    private CardResult resolveInteraction(Inbound source, Pending item, String action, String supplied) {
        synchronized (item) {
        if (!item.peerKey.equals(source.peerKey()) || !settings.get(source.channel()).permits(source))
            return new CardResult(false, "只能由任务发起人在原会话中处理", null);
        if (!item.card.pending()) return new CardResult(false, item.card.stateLabel() + "，没有重复操作。", item.card);
        if (!live(item)) {
            closeInteraction(item, System.currentTimeMillis() >= item.expiresAt ? "EXPIRED" : "CLOSED");
            return new CardResult(false, item.card.stateLabel() + "。请发送 /状态 查看。", item.card);
        }
        boolean allow = "approve".equals(action);
        boolean answer = "answer".equals(action);
        boolean accepted;
        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("source", "im"); resolution.put("channel", source.channel());
        resolution.put("userId", source.userId()); resolution.put("chatId", source.chatId()); resolution.put("messageId", source.messageId());
        switch (item.kind) {
            case "approval" -> {
                if (answer) return new CardResult(false, "这是一项操作审批，请点击批准／拒绝按钮，或使用带编号的命令。", item.card);
                resolution.put("decision", allow ? "allow" : "deny");
                accepted = approvals.recordDecision(item.threadId, item.callId, "", allow ? "allow" : "deny");
            }
            case "plan_review" -> {
                if (answer) return new CardResult(false, "这是一项计划确认，请点击同意／退回，或使用带编号的命令。", item.card);
                String feedback = supplied;
                resolution.put("decision", allow ? "approve" : "reject"); resolution.put("feedback", feedback);
                accepted = plans.record(item.threadId, item.callId, allow ? PlanReviewService.PlanDecision.approve()
                    : PlanReviewService.PlanDecision.reject(feedback));
            }
            case "question" -> {
                if (!answer || supplied.isBlank()) {
                    return new CardResult(false, "请点选答案或直接回复；也可使用 /回答 " + item.code + " 内容。", item.card);
                }
                String value = supplied;
                Object values = item.payload.get("options");
                if (values instanceof List<?> options) {
                    String selected = ImAnswerParser.choice(value, options);
                    if (selected != null) value = selected;
                    if (Boolean.FALSE.equals(item.payload.get("allowFreeText")) && !options.contains(value)) {
                        return new CardResult(false, "请选择提示中的选项编号或完整选项文字。", item.card);
                    }
                }
                resolution.put("answer", value); accepted = prompts.recordAnswer(item.threadId, item.callId, value);
            }
            default -> { return new CardResult(false, "不支持此交互类型。", item.card); }
        }
        if (accepted) audit.recordResolved(item.kind, item.threadId, item.callId, resolution);
        closeInteraction(item, accepted ? answer ? "ANSWERED" : allow ? "APPROVED" : "DENIED" : "CLOSED");
        return new CardResult(accepted, accepted ? (answer ? "回答已提交，任务继续处理。" : allow ? "已确认本次请求；后续操作仍按权限逐项检查。" : "已拒绝本次请求。")
            : "请求已过期或已被处理，没有重复操作。", item.card);
        }
    }
    CardResult cardAction(CardAction action) {
        ImJournal.CardRecord record = action == null ? null : journal.card(action.cardId());
        if (record == null) return new CardResult(false, "当前工作区：" + workspace.label() + "。该请求不属于此工作区或已清理，请发送 /工作区 查看", null);
        Inbound owner = record.source;
        String space = "single".equals(owner.chatType()) ? owner.userId() : owner.chatId();
        if (!owner.channel().equals(action.channel()) || !owner.account().equals(action.account()))
            return new CardResult(false, "此卡片不属于当前机器人连接，请在原会话发送 /状态", null);
        if (!owner.userId().equals(action.userId()))
            return new CardResult(false, "仅任务发起人可以处理这张卡片，请使用发起任务的账号", null);
        if (!settings.get(owner.channel()).permits(owner))
            return new CardResult(false, "此会话授权已失效，请在 Harnex 中重新确认授权", null);
        // DingTalk's authenticated card callback can omit space metadata (its Python SDK only
        // consumes userId + outTrackId). The non-forwardable card's persisted source is authoritative.
        // Still reject any contradictory supplied space. WeCom and Feishu require the original conversation.
        boolean optionalSpace = "dingtalk".equals(owner.channel());
        String suppliedType = action.spaceType() == null ? "" : action.spaceType();
        String suppliedSpace = action.spaceId() == null ? "" : action.spaceId();
        // The actual DingTalk callback uses spaceType=im and the original conversationId.
        // IM_GROUP / IM_ROBOT are delivery-space names, not the only callback-space names.
        boolean imConversation = "im".equals(suppliedType);
        boolean sameSpace = imConversation && owner.chatId().equals(suppliedSpace)
            || (!imConversation || optionalSpace) && space.equals(suppliedSpace);
        if ((!optionalSpace || !suppliedType.isBlank()) && !imConversation && !owner.chatType().equals(suppliedType)
            || (!optionalSpace || !suppliedSpace.isBlank()) && !sameSpace)
            return new CardResult(false, "卡片所在会话与任务来源不一致，请在原会话发送 /状态", null);
        if (!record.value.pending()) return new CardResult(false, record.value.stateLabel() + "，没有重复操作。", record.value);
        Pending item = pending.get(record.value.code());
        if (item == null || !item.card.id().equals(action.cardId()))
            return new CardResult(false, "该卡片对应的请求已结束，请查看最新请求", record.value.withState("CLOSED"));
        if (action.eventId() == null || action.eventId().isBlank()) return new CardResult(false, "点击缺少身份标识，请重试", null);
        Inbound event = new Inbound(owner.channel(), owner.account(), owner.userId(), owner.chatId(), owner.userName(),
            "card:" + action.eventId(), "/card", owner.replyUrl(), owner.replyExpiresAt(), owner.requestId(), owner.chatType());
        ImJournal.Receipt receipt = journal.accept(event);
        if (receipt == null) return new CardResult(false, "此点击已处理，请查看卡片状态", item.card);
        CardResult result;
        synchronized (item) {
            if (!live(item)) {
                closeInteraction(item, System.currentTimeMillis() >= item.expiresAt ? "EXPIRED" : "CLOSED");
                result = new CardResult(false, item.card.stateLabel() + "，没有重复操作。", item.card);
            } else if (item.card.buttons().stream().noneMatch(button -> button.action().equals(action.action()))) {
                result = new CardResult(false, "该按钮不属于当前请求", null);
            } else if ("details".equals(action.action())) {
                enqueue(owner, "请求 " + item.code + "\n" + item.card.details(), item.expiresAt);
                result = new CardResult(true, "详情已加入发送队列，请查看原会话", item.card);
            } else if ("cancel".equals(action.action())) {
                boolean cancelled = runs.cancel(item.runId);
                if (cancelled) closeInteraction(item, "CANCELLED");
                result = new CardResult(cancelled, cancelled ? "正在停止任务，已完成的操作不会自动撤销" : "任务已结束", item.card);
            } else {
                String verb = action.action().startsWith("answer:") ? "answer" : action.action();
                String value = action.action().startsWith("answer:") ? action.action().substring(7) : "";
                result = resolveInteraction(event, item, verb, value);
            }
        }
        journal.complete(receipt.id, result.message());
        return result;
    }
    private void closeInteraction(Pending item, String status) {
        synchronized (item) {
            if (!item.card.pending()) return;
            item.card = item.card.withState(status);
            journal.putCard(item.source, item.card);
        }
    }
    private void maintainCards() {
        for (Pending item : pending.values()) {
            synchronized (item) {
                if (item.card.pending() && !live(item)) closeInteraction(item,
                    System.currentTimeMillis() >= item.expiresAt ? "EXPIRED" : "CLOSED");
            }
        }
        for (var card : journal.cardUpdates()) {
            if ("TEXT".equals(card.mode)) {
                if (Set.of("EXPIRED", "CANCELLED", "CLOSED", "RESTARTED").contains(card.value.state()))
                    enqueue(card.source, "请求 " + card.value.code() + "：" + card.value.stateLabel() + "。旧编号已失效。", 0);
                journal.cardDelivered(card.value.id(), "TEXT", card.value.revision());
            } else journal.enqueueCard(card, true);
        }
        pending.values().removeIf(item -> !item.card.pending() && item.expiresAt < System.currentTimeMillis() - 3600_000);
    }
    private boolean live(Pending item) {
        Active task = active.get(item.peerKey);
        return item.card.pending() && task != null && task.runId.equals(item.runId) && item.expiresAt > System.currentTimeMillis()
            && interactionSource.collect(item.threadId).stream().anyMatch(p -> p.callId().equals(item.callId) && p.kind().equals(item.kind));
    }
    private void event(Inbound source, StreamEvent event) {
        if ("tool_use_start".equals(event.getType()) && event.getData() != null) {
            String tool = String.valueOf(event.getData().getOrDefault("name", event.getData().getOrDefault("toolName", "")));
            progress.put(source.peerKey(), switch (tool) {
                case "file_read", "read_file", "glob", "grep", "search", "list_directory" -> "正在查看项目文件";
                case "file_write", "file_edit", "multi_edit" -> "正在修改项目文件";
                case "bash" -> "正在运行命令";
                case "web_search", "web_fetch" -> "正在查阅资料";
                case "delegate" -> "正在处理子任务";
                default -> "正在处理任务";
            });
        } else if ("tool_use_end".equals(event.getType())) {
            progress.put(source.peerKey(), "正在整理工具结果");
        }
        String kind = switch (event.getType()) {
            case "approval_required" -> "approval";
            case "plan_review" -> "plan_review";
            case "user_question" -> "question";
            default -> "";
        };
        if (kind.isEmpty()) return;
        Active task = active.get(source.peerKey());
        if (task == null || event.getData() == null) return;
        String callId = String.valueOf(event.getData().getOrDefault("callId", ""));
        if (callId.isBlank()) return;
        long expires = event.getData().get("expiresAt") instanceof Number value ? value.longValue()
            : System.currentTimeMillis() + ("approval".equals(kind) ? 10L : 30L) * 60_000;
        expires = Math.min(expires, task.startedAt + 30L * 60_000);
        Pending item;
        do {
            String code = ImTypes.id().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
            ImInteractionCard card = ImInteractionCard.create("im_" + ImTypes.id().replace("-", ""), code, kind,
                event.getData(), source, cfg.getWorkspace().getRoot(), expires);
            item = new Pending(source, task, callId, Map.copyOf(event.getData()), card);
        } while (pending.putIfAbsent(item.code, item) != null);
        journal.putCard(source, item.card);
        journal.enqueueCard(journal.card(item.card.id()), false);
    }
    private String render(Pending item) {
        return item.card.text();
    }
    private static String help() {
        return "直接发送文字即可开始任务。\n/模型 — 查看模型列表；/模型 编号 切换；/模型 默认 恢复默认\n/强度 — 查看与选择推理强度\n/模式 — 查看与选择 ask / yolo\n/状态 — 当前任务与待处理问题\n/取消 — 停止当前任务\n/结果 — 查看上一项任务结果\n"
            + "/新建 — 开始新话题，保留原会话\n/会话 — 查看最近会话；/继续 编号 返回会话\n/工作区 — 查看当前项目\n/帮助 — 查看命令\n问题可点选或直接回复；审批请点对应按钮或使用编号。群聊请 @机器人，每个人独立保留上下文。";
    }
    private void finish(ImJournal.Receipt receipt, String reply) {
        journal.complete(receipt.id, reply);
        enqueue(receipt.source, reply, 0);
    }
    private static String finalReply(String reply) {
        if (reply == null || reply.isBlank()) return "任务结束，没有文本结果。";
        if (reply.startsWith("[agent cancelled]")) return "任务已取消。已完成的操作不会自动撤销。";
        if (reply.startsWith("[agent timeout]")) return "任务达到运行时限，已请求停止。请在 Harnex 查看已完成的操作，未自动重新执行。";
        if (reply.startsWith("[agent error]")) return reply + "\n模型无法连通时，可发送 /模型 查看并切换。切换后请重新发送任务，原任务不会自动重做。";
        return reply;
    }
    private void enqueue(Inbound source, String text, long expiresAt) {
        enqueue(source, text, expiresAt, "");
    }
    private void enqueue(Inbound source, String text, long expiresAt, String interactionCode) {
        enqueue(source, text, expiresAt, interactionCode, "");
    }
    private void enqueueProgress(Inbound source, String runId, String text) {
        enqueue(source, text, System.currentTimeMillis() + 60_000, "", runId);
    }
    private void enqueue(Inbound source, String text, long expiresAt, String interactionCode, String runId) {
        if (text == null || text.isBlank()) return;
        String label = source.userName() == null || source.userName().isBlank() ? source.userId() : source.userName();
        label = label.replaceAll("[\\p{Cntrl}\\[\\]<>]", " ");
        if (label.length() > 60) label = label.substring(0, 60);
        // Label the recipient so concurrent users in one group can distinguish answers and approval prompts.
        text = "工作区：" + workspace.label() + "\n【" + label + "】\n" + text;
        String previousId = "";
        boolean authorized = settings.get(source.channel()).permits(source);
        for (String chunk : chunks(text, "wecom".equals(source.channel()) ? 3800 : 1800))
            previousId = journal.enqueue(source, chunk, expiresAt, previousId, interactionCode, authorized, runId);
    }
    /** Split at Unicode code-point boundaries, respecting the platform's byte limit including numbering. */
    static List<String> chunks(String text, int maxBytes) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder(); int bytes = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset); String value = new String(Character.toChars(cp));
            int size = value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes - 40 && !part.isEmpty()) { parts.add(part.toString()); part.setLength(0); bytes = 0; }
            part.append(value); bytes += size; offset += Character.charCount(cp);
        }
        if (!part.isEmpty()) parts.add(part.toString());
        if (parts.size() > 1) for (int i = 0; i < parts.size(); i++) parts.set(i, "(" + (i + 1) + "/" + parts.size() + ")\n" + parts.get(i));
        return parts;
    }
    /** Slow tasks receive a concise heartbeat; waiting cards already tell the user what to do. */
    void maintainProgress(long now) {
        for (Active task : active.values()) {
            String peer = task.source.peerKey();
            long due = nextProgressAt.getOrDefault(peer, Long.MAX_VALUE);
            if (now < due) continue;
            nextProgressAt.put(peer, now + 180_000);
            if (pending.values().stream().anyMatch(item -> item.runId.equals(task.runId) && live(item))) continue;
            long seconds = Math.max(0, (now - task.startedAt) / 1000);
            String elapsed = seconds < 60 ? seconds + " 秒" : seconds / 60 + " 分钟";
            enqueueProgress(task.source, task.runId, progress.getOrDefault(peer, "正在分析任务")
                + "，已运行 " + elapsed + "。\n完成后会主动发回结果；/取消 可停止任务。");
        }
    }
    void deliver() {
        if (closed || !delivering.compareAndSet(false, true)) return;
        try {
            if (taskNotifications != null) taskNotifications.reconcile();
            maintainCards();
            maintainProgress(System.currentTimeMillis());
            for (ImJournal.Delivery item : journal.ready()) {
                if (closed) return;
                Connection config = settings.get(item.destination.channel());
                if (!config.clientId().equals(item.destination.account())) {
                    journal.deliveryState(item, "FAILED", "机器人已变化，未向新机器人发送旧回复", 0); continue;
                }
                if (item.requiresAuthorization && !config.authorizes(item.destination)) {
                    journal.deliveryState(item, "FAILED", "该会话授权已移除，未继续发送结果", 0); continue;
                }
                if ("NOTIFICATION".equals(item.kind) && taskNotifications != null && !taskNotifications.currentReminder(item)) {
                    journal.deliveryState(item, "EXPIRED", "任务状态已变化，未发送过期提醒", 0); continue;
                }
                if (!config.enabled()) continue;
                if (!item.interactionCode.isEmpty()) {
                    Pending interaction = pending.get(item.interactionCode);
                    if (interaction == null || !live(interaction)) {
                        journal.deliveryState(item, "EXPIRED", "交互已结束，未发送过期提示", 0); continue;
                    }
                }
                if (!item.runId.isEmpty() && !"NOTIFICATION".equals(item.kind)) {
                    Active task = active.get(item.destination.peerKey());
                    if (task == null || !task.runId.equals(item.runId)) {
                        journal.deliveryState(item, "EXPIRED", "任务已结束，未发送过期进度", 0); continue;
                    }
                }
                if (item.expiresAt > 0 && item.expiresAt <= System.currentTimeMillis()) {
                    journal.deliveryState(item, "EXPIRED", "提示已过期，请发送 /状态 查看", 0); continue;
                }
                Transport transport = transports.get(item.destination.channel());
                if (transport == null || !"CONNECTED".equals(transport.status().get("state"))) continue;
                journal.deliveryState(item, "SENDING", "", 0);
                try {
                    if ("CARD".equals(item.kind)) {
                        boolean nativeCard;
                        try { nativeCard = transport.sendCard(item.destination, item.card); }
                        catch (DeliveryException failure) {
                            if (failure.kind != DeliveryException.Kind.PERMANENT) throw failure;
                            nativeCard = false;
                        }
                        if (!nativeCard) enqueue(item.destination, item.card.text(), item.expiresAt, item.interactionCode);
                        journal.cardDelivered(item.card.id(), nativeCard ? "CARD" : "TEXT", item.card.revision());
                    } else if ("CARD_UPDATE".equals(item.kind)) {
                        boolean updated = transport.updateCard(item.destination, item.card);
                        if (!updated) enqueue(item.destination, "请求 " + item.card.code() + "：" + item.card.stateLabel() + "。旧按钮已失效。", 0);
                        journal.cardDelivered(item.card.id(), "CARD", item.card.revision());
                    } else if ("NOTIFICATION".equals(item.kind)) transport.sendNotification(item.destination, item.id, item.text);
                    else transport.send(item.destination, item.id, item.text);
                    journal.deliveryState(item, "DELIVERED", "", 0);
                } catch (DeliveryException e) {
                    if ("CARD".equals(item.kind) && e.kind == DeliveryException.Kind.UNKNOWN) {
                        journal.cardDelivered(item.card.id(), "CARD", 0);
                        enqueue(item.destination, "需要处理请求 " + item.card.code() + "。卡片发送结果尚未确认；如未看到卡片，请发送 /状态 查看操作内容。",
                            item.expiresAt, item.interactionCode);
                    }
                    if ("CARD_UPDATE".equals(item.kind) && (e.kind == DeliveryException.Kind.PERMANENT || item.attempts >= 5)) {
                        enqueue(item.destination, "请求 " + item.card.code() + "：" + item.card.stateLabel() + "。卡片未能同步，请以此状态为准；旧按钮已失效。", 0);
                        journal.cardDelivered(item.card.id(), "CARD", item.card.revision());
                    }
                    String status = "CARD_UPDATE".equals(item.kind) && e.kind != DeliveryException.Kind.PERMANENT && item.attempts < 5 ? "PENDING"
                        : e.kind == DeliveryException.Kind.UNKNOWN ? "UNKNOWN"
                        : e.kind == DeliveryException.Kind.RETRYABLE && item.attempts < 5 ? "PENDING" : "FAILED";
                    journal.deliveryState(item, status, e.getMessage(), System.currentTimeMillis() + Math.min(60_000, 2000L << item.attempts));
                }
            }
            deliveryError = "";
        } catch (RuntimeException ignored) {
            // A disk failure leaves SENDING as unknown at restart; never silently resend a possibly delivered message.
            deliveryError = "回复投递暂时停止，请检查工作区磁盘和运行日志。可以在 Agent 会话中查看任务结果。";
        } finally {
            delivering.set(false);
        }
    }
    @PreDestroy public synchronized void close() {
        closed = true; generations.clear(); transports.values().forEach(Transport::close); transports.clear();
        commands.shutdown(); deliveries.shutdown();
        active.values().forEach(task -> runs.cancel(task.runId));
        drain(commands);
        // A command already crossing admission when close began may have acquired its Run after the first pass.
        active.values().forEach(task -> runs.cancel(task.runId));
        drain(deliveries);
        pending.clear();
    }
    private static void drain(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow(); Thread.currentThread().interrupt();
        }
    }
}
