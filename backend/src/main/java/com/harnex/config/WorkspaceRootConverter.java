package com.harnex.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Lets a bare scalar bound to {@code harnex.workspace} be read as {@link AgentConfig.Workspace#root}.
 *
 * <p>
 * The product's long-documented {@code HARNEX_WORKSPACE} environment variable is the primary way the desktop shell
 * ({@code src-tauri}), the Supervisor ({@code bin/lib/supervisor.mjs}) and direct {@code java -jar} users point the agent
 * at a project
 * folder. Spring Boot's relaxed binding maps {@code HARNEX_WORKSPACE} → the property name {@code harnex.workspace},
 * which is the complex {@link AgentConfig.Workspace} object — so without this converter the binder raises
 * {@code ConverterNotFoundException} ("String → Workspace") and the whole application fails to start whenever the env
 * var (or a {@code harnex.workspace: <path>} scalar) is present.
 *
 * <p>
 * This converter treats that scalar as the workspace {@code root}, leaving every other field at its default — exactly
 * the intent of the env var. The nested form ({@code harnex.workspace.root=...} / {@code HARNEX_WORKSPACE_ROOT}) is
 * unaffected: the converter only fires when a scalar sits directly at {@code harnex.workspace}.
 */
@Component
@ConfigurationPropertiesBinding
public class WorkspaceRootConverter implements Converter<String, AgentConfig.Workspace> {

    @Override
    public AgentConfig.Workspace convert(String source) {
        AgentConfig.Workspace ws = new AgentConfig.Workspace();
        ws.setRoot(source == null ? "" : source.trim());
        return ws;
    }
}
