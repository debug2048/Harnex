package com.harnex.gateway.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.storage.SharedFileTransaction;
import com.harnex.config.UserDataPaths;
import com.harnex.execution.ScopeIdentity;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Remembers which project an authorized conversation last addressed, across Runtime handoffs. */
@Component
@ProjectRuntime
public final class ImWorkspaceContext {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path directory;
    private final ScopeIdentity scope;
    private final String label;
    private final String machine;
    record Visit(String scopeId, String label) {}

    public ImWorkspaceContext(AgentConfig cfg, ScopeIdentity scope) {
        directory = UserDataPaths.resolve(cfg).resolve("im-contexts");
        this.scope = scope;
        Path root = scope.projectRoot();
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        name = name.replaceAll("[\\p{Cntrl}]", " ");
        if (name.codePointCount(0, name.length()) > 64) name = name.substring(0, name.offsetByCodePoints(0, 64));
        String host;
        try { host = InetAddress.getLocalHost().getHostName(); }
        catch (UnknownHostException unavailable) { host = System.getProperty("os.name", "Harnex"); }
        machine = host;
        label = name + " · " + machine + "（" + scope.scopeId() + "）";
    }
    public String label() { return label; }
    public String machine() { return machine; }
    public String description() { return "运行位置：" + machine + "\n项目路径：" + scope.projectRoot() + "\n消息由当前持有机器人连接的工作区处理。"; }

    /** Empty on first contact/same project; failures stop admission rather than guessing the target. */
    String visit(ImTypes.Inbound source) {
        Path file = directory.resolve(source.peerKey() + ".json");
        return SharedFileTransaction.withExclusiveLock(file, () -> {
            Visit previous = Files.exists(file) ? JSON.readValue(Files.readAllBytes(file), Visit.class) : null;
            if (previous != null && scope.scopeId().equals(previous.scopeId())) return "";
            SharedFileTransaction.writeAtomically(file, JSON.writeValueAsBytes(new Visit(scope.scopeId(), label)), false, true);
            return previous == null ? "" : "机器人已从工作区 " + previous.label() + " 切换到 " + label + "。";
        });
    }
}
