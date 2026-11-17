package com.harnex.gateway.im;

import com.harnex.config.ProjectRuntime;
import com.harnex.execution.TextTarget;
import com.harnex.config.AgentConfig;
import com.harnex.gateway.im.ImTypes.Inbound;
import com.harnex.provider.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Local control commands: listing and selecting models never call an upstream model. */
@Component
@ProjectRuntime
public final class ImModelCommands {
    private final ImJournal journal;
    private final ProviderRouter router;
    private final ResolvedProviderRegistry providers;
    private final TextTargetResolver targets;
    private final AgentConfig config;
    private final ImConfigStore connections;
    // Numbers refer to the exact list this peer last requested. Restart requires a fresh list.
    private final Map<String, List<TextTarget>> menus = new LinkedHashMap<>();

    public ImModelCommands(ImJournal journal, ProviderRouter router, ResolvedProviderRegistry providers, TextTargetResolver targets,
        AgentConfig config, ImConfigStore connections) {
        this.journal = journal; this.router = router; this.providers = providers; this.targets = targets;
        this.config = config; this.connections = connections;
    }

    public TextTarget target(Inbound source) {
        TextTarget target = targets.resolve(journal.selectedModel(source));
        if (target.reasoningEffort() != null && !efforts(target).contains(target.reasoningEffort()))
            throw new ProviderException("text_effort_unavailable", "The selected reasoning effort is no longer supported");
        return target;
    }

    public String selection(Inbound source) {
        TextTarget selected = journal.selectedModel(source);
        boolean followsDefault = selected == null || selected.provider() == null;
        String description = followsDefault ? "跟随默认" : label(selected);
        try {
            TextTarget resolved = target(source);
            return (followsDefault ? description + "（" + label(resolved) + "）" : label(resolved))
                + " · 强度 " + (resolved.reasoningEffort() == null ? "auto" : resolved.reasoningEffort());
        }
        catch (ProviderException unavailable) { return description + "（当前配置不可用，请发送 /模型 选择其他模型）"; }
    }

    public synchronized String execute(Inbound source, String argument) {
        String requested = argument == null ? "" : argument.trim();
        if (requested.isEmpty() || Set.of("列表", "list").contains(requested.toLowerCase(Locale.ROOT))) return list(source);
        if (Set.of("默认", "default", "reset").contains(requested.toLowerCase(Locale.ROOT))) {
            journal.selectModel(source, null);
            return "已恢复跟随默认模型。\n下次任务模型：" + selection(source) + scope();
        }
        TextTarget selected;
        if (requested.matches("[0-9]+")) {
            List<TextTarget> shown = menus.get(source.peerKey());
            if (shown == null) return "请先发送 /模型 查看列表，再使用 /模型 编号 选择。";
            int index;
            try { index = Integer.parseInt(requested) - 1; }
            catch (NumberFormatException invalid) { return "模型编号不正确，请发送 /模型 查看列表。"; }
            if (index < 0 || index >= shown.size()) return "模型编号不在列表中，请发送 /模型 查看列表。";
            selected = shown.get(index);
        } else {
            selected = catalogue().stream().filter(item -> label(item).equals(requested)).findFirst().orElse(null);
            if (selected == null) return "未找到该模型。请发送 /模型，再用 /模型 编号 或列表中的完整“服务商:模型”选择。";
        }
        try { selected = targets.resolve(selected); }
        catch (ProviderException unavailable) { return "该模型已不可用，未修改原选择。请重新发送 /模型 查看当前列表。"; }
        journal.selectModel(source, selected);
        return "已选择：" + label(selected) + "。强度已恢复 auto，从下一条任务生效。" + scope()
            + "\n选择已保存，尚未测试连通性；请重新发送需要处理的任务。";
    }

    public String effort(Inbound source, String argument) {
        String value = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        if (Set.of("auto", "默认", "default").contains(value)) {
            TextTarget old = journal.selectedModel(source);
            journal.selectModel(source, old == null || old.provider() == null ? null : new TextTarget(old.provider(), old.model(), null));
            return "强度已恢复 auto（跟随模型配置），从下一条任务生效。" + scope();
        }
        TextTarget target;
        try { target = targets.resolve(journal.selectedModel(source)); }
        catch (ProviderException unavailable) { return "当前模型配置不可用，请先发送 /模型 选择模型；可用 /强度 auto 清除原强度。"; }
        List<String> supported = efforts(target);
        if (value.isEmpty()) return "当前强度：" + (target.reasoningEffort() == null ? "auto" : target.reasoningEffort())
            + "\n模型：" + label(target) + "\n可选：auto" + (supported.isEmpty() ? "（该模型未声明可调强度）" : "、" + String.join("、", supported))
            + "\n使用 /强度 档位 设置，从下一条任务生效。";
        if (!supported.contains(value)) return "该模型不支持此强度，未修改设置。请发送 /强度 查看可选档位。";
        TextTarget old = journal.selectedModel(source);
        journal.selectModel(source, new TextTarget(old == null ? null : old.provider(), old == null ? null : old.model(), value));
        return "强度已选择：" + value + "，从下一条任务生效。" + scope();
    }

    private List<String> efforts(TextTarget target) {
        var provider = providers.find(target.provider()).orElseThrow(() -> new ProviderException("text_provider_unavailable", "Provider unavailable"));
        var profile = ProviderModelCapabilities.from(config, provider).profile(target.model());
        if (!profile.reasoningEffortsDeclared()) return List.of();
        String required = profile.functionToolReasoningEffort();
        return List.of("none", "minimal", "low", "medium", "high", "xhigh", "max").stream()
            .filter(profile.reasoningEfforts()::contains)
            .filter(effort -> required == null || required.isBlank() || required.equals(effort)).toList();
    }

    public String permissionMode(Inbound source) {
        return connections.get(source.channel()).allowYolo() && "yolo".equals(journal.permissionMode(source)) ? "yolo" : "ask";
    }

    public String mode(Inbound source, String argument) {
        String value = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        boolean allowed = connections.get(source.channel()).allowYolo();
        if (value.isEmpty()) return "当前模式：" + permissionMode(source)
            + "\n/模式 ask — 按工具策略请求确认\n/模式 yolo — 自动执行普通工具操作（需本地允许）"
            + (allowed ? "" : "\nHarnex 尚未允许 IM 使用 YOLO。")
            + "\n工作区权限、禁用工具及全局审批策略仍生效。";
        if (!Set.of("ask", "yolo").contains(value)) return "模式只支持 ask 或 yolo，例如 /模式 ask。";
        if ("yolo".equals(value) && !allowed) return "未切换：请先在 Harnex「设置 → IM 客户端」允许修改工作区，并开启“允许在 IM 中选择 YOLO”后保存。";
        journal.permissionMode(source, value);
        return "模式已选择：" + value + "，从下一条任务生效。" + scope()
            + ("yolo".equals(value) ? "\n普通工具操作将自动执行，工作区权限和禁用规则仍生效。可用 /模式 ask 恢复确认。" : "");
    }

    private String list(Inbound source) {
        List<TextTarget> entries = catalogue();
        if (!menus.containsKey(source.peerKey()) && menus.size() >= 1000) menus.remove(menus.keySet().iterator().next());
        menus.put(source.peerKey(), entries);
        StringBuilder reply = new StringBuilder("下次任务模型：").append(selection(source));
        if (entries.isEmpty()) return reply.append("\n当前没有已启用的模型。请先在 Harnex「设置 → 模型」配置并启用模型，按设置提示重启 Runtime。").toString();
        reply.append("\n已配置且启用的模型（未进行联网测试）：");
        for (int index = 0; index < entries.size(); index++) reply.append("\n").append(index + 1).append(". ").append(label(entries.get(index)));
        return reply.append("\n发送 /模型 编号 切换，例如 /模型 1；也可发送 /模型 服务商:模型。\n/模型 默认 — 恢复跟随默认模型。")
            .append(scope()).toString();
    }

    private List<TextTarget> catalogue() {
        Set<String> enabled = new HashSet<>();
        router.listAvailableProviderNames().forEach(name -> enabled.add(name.toLowerCase(Locale.ROOT)));
        Map<String, TextTarget> result = new LinkedHashMap<>();
        for (ResolvedProvider provider : providers.providers()) {
            if (!enabled.contains(provider.getName().toLowerCase(Locale.ROOT))) continue;
            List<String> names = new ArrayList<>();
            names.add(provider.getDefaultModel());
            provider.getModels().forEach(model -> names.add(model.getId()));
            for (String model : names) {
                if (model == null || model.isBlank()) continue;
                TextTarget target = new TextTarget(provider.getName(), model.trim(), null);
                result.putIfAbsent(label(target), target);
            }
        }
        return List.copyOf(result.values());
    }

    public static String label(TextTarget target) { return target.provider() + ":" + target.model(); }
    private static String scope() { return "\n仅对你在当前机器人会话中生效。"; }
}
