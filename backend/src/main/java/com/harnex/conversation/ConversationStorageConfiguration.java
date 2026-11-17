package com.harnex.conversation;

import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.harnex.artifact.ArtifactStore;
import com.harnex.artifact.FileArtifactStore;
import com.harnex.config.AgentConfig;
import com.harnex.execution.ScopeIdentity;
import com.harnex.run.ProjectConversationLocks;

@Configuration
public class ConversationStorageConfiguration {
    @Bean public ConversationRepository conversationRepository(AgentConfig config, ScopeIdentity scope, ProjectStorage storage,
        ProjectConversationLocks ownership) {
        return new SharedConversationRepository(storage, scope.scopeId(), ownership);
    }
    @Bean(destroyMethod = "close") public LuceneConversationSearch conversationSearch(
        AgentConfig config, ScopeIdentity scope, ConversationRepository repository) {
        return new LuceneConversationSearch(Path.of(config.getDataDir()), scope.scopeId(), repository);
    }
    @Bean public ArtifactStore artifactStore(AgentConfig config, ScopeIdentity scope, ProjectStorage storage) {
        return new FileArtifactStore(storage, scope.scopeId());
    }
}
