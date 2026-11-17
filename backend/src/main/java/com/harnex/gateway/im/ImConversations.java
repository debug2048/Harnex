package com.harnex.gateway.im;

import com.harnex.config.ProjectRuntime;
import com.harnex.gateway.im.ImTypes.Inbound;
import com.harnex.session.SessionStore;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** A chat can revisit only its own recorded conversations; numbering is bound to the last displayed list. */
@Component
@ProjectRuntime
public final class ImConversations {
    private final ImJournal journal;
    private final SessionStore store;
    private final Map<String, Selection> selections = new ConcurrentHashMap<>();
    private record Selection(List<ImJournal.Conversation> items, long expiresAt) {}

    public ImConversations(ImJournal journal, SessionStore store) { this.journal = journal; this.store = store; }
    String routingKey(Inbound source) {
        String key = journal.conversationKey(source);
        return key.isEmpty() ? ImTypes.key(source.account(), source.chatId()) : ImTypes.key(source.account(), source.chatId(), key);
    }
    void started(Inbound source, String threadId) { journal.rememberConversation(source, threadId); }
    String current(Inbound source) {
        String key = journal.conversationKey(source);
        return journal.conversations(source).stream().filter(item -> item.key().equals(key))
            .map(ImJournal.Conversation::title).findFirst().orElse("新会话");
    }
    String fresh(Inbound source) {
        journal.selectConversation(source, ImTypes.id());
        return "已开始新会话，原会话已保留。直接发送任务即可。\n模型与权限设置保持不变；/会话 可找回之前的会话。";
    }
    String list(Inbound source, String query) {
        String filter = query.toLowerCase(Locale.ROOT).trim();
        List<ImJournal.Conversation> items = journal.conversations(source).stream()
            .filter(item -> !store.isDeletedRecently(item.threadId()))
            .filter(item -> filter.isEmpty() || item.title().toLowerCase(Locale.ROOT).contains(filter))
            .filter(item -> store.get(item.threadId()).isPresent()).limit(10).toList();
        selections.entrySet().removeIf(entry -> entry.getValue().expiresAt < System.currentTimeMillis());
        selections.put(source.peerKey(), new Selection(items, System.currentTimeMillis() + 600_000));
        if (items.isEmpty()) return filter.isEmpty() ? "还没有可继续的会话。直接发送任务即可开始。" : "没有找到匹配的会话。可用 /会话 查看最近会话。";
        String selected = journal.conversationKey(source);
        var time = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
        StringBuilder result = new StringBuilder("你的最近会话：\n");
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            result.append(i + 1).append(". ").append(item.title());
            if (item.key().equals(selected)) result.append(" · 当前");
            result.append("\n   ").append(time.format(Instant.ofEpochMilli(item.updatedAt()))).append('\n');
        }
        return result + "\n发送 /继续 编号 切换（编号 10 分钟内有效），/新建 开始新话题。\n只列出你在当前机器人、聊天和工作区中的会话；/会话 关键词 可查找。";
    }
    String resume(Inbound source, String argument) {
        if (argument.isBlank()) return list(source, "");
        Selection selection = selections.get(source.peerKey());
        if (selection == null || selection.expiresAt < System.currentTimeMillis()) return "请先发送 /会话 查看最新列表，再用 /继续 编号 选择。";
        int index;
        try { index = Integer.parseInt(argument.trim()) - 1; }
        catch (NumberFormatException invalid) { return "请输入列表中的编号，例如 /继续 1。"; }
        if (index < 0 || index >= selection.items.size()) return "这个编号不在刚才的列表中。请发送 /会话 重新查看。";
        var item = selection.items.get(index);
        if (journal.conversations(source).stream().noneMatch(owned -> owned.key().equals(item.key()) && owned.threadId().equals(item.threadId()))
            || store.isDeletedRecently(item.threadId()) || store.get(item.threadId()).isEmpty())
            return "这个会话已删除或不可用。请发送 /会话 查看其他会话。";
        journal.selectConversation(source, item.key());
        return "已继续会话：" + item.title() + "。\n直接发送下一条消息即可；之前的任务不会自动重新执行。";
    }
    ImJournal.Receipt latest(Inbound source) {
        String key = journal.conversationKey(source);
        var selected = journal.conversations(source).stream().filter(item -> item.key().equals(key)).findFirst();
        return selected.isPresent() ? journal.latest(source, selected.get().threadId()) : key.isEmpty() ? journal.latest(source.peerKey()) : null;
    }
}
