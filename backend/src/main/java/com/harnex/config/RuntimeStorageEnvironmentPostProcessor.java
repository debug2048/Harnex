package com.harnex.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Resolve and protect every data owner before logging or any bean can write files. */
public final class RuntimeStorageEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 2; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        AgentConfig config = new AgentConfig();
        config.setUserDataDir(environment.getProperty("harnex.user-data-dir"));
        config.setRuntimeProfile(RuntimeProfile.valueOf(environment.getProperty("harnex.runtime-profile", "PROJECT").toUpperCase(Locale.ROOT)));
        config.getWorkspace().setRoot(environment.getProperty("harnex.workspace.root"));
        config.setDataDir(environment.getProperty("harnex.data-dir"));
        config.setSharedDataDir(environment.getProperty("harnex.shared-data-dir"));
        if (config.getRuntimeProfile() == RuntimeProfile.PROJECT
            && !Files.isDirectory(RuntimeDataPaths.projectRoot(config))) {
            throw new IllegalArgumentException("Project root does not exist: " + RuntimeDataPaths.projectRoot(config));
        }
        String data = RuntimeDataPaths.prepare(config).toString();
        String logs = environment.getProperty("harnex.log.dir", Path.of(data).resolve("diagnostics/logs").toString());
        environment.getPropertySources().addFirst(new MapPropertySource("runtimeStoragePaths",
            Map.of("harnex.data-dir", data, "harnex.log.archive-pattern", LogArchivePattern.forDirectory(logs))));
    }
}
