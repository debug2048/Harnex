package com.harnex.web.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import lombok.extern.slf4j.Slf4j;

/**
 * Optionally serve a pre-built single-page front-end from the same origin as the API.
 *
 * <p>
 * The backend is normally API-only — in the standard Web UI flow {@code bin/lib/supervisor.mjs} (Node) serves the SPA and
 * reverse-proxies {@code /api}. The desktop shell, however, bundles only a JRE (no Node), so it cannot run the
 * Supervisor; it
 * spawns this jar and points a webview at {@code http://127.0.0.1:<port>}. For that to work the jar itself has to serve
 * the SPA. Rather than baking the front-end into the jar (which would bloat every deployment and couple the backend
 * build to the front-end build), we serve it from an external directory supplied at launch:
 *
 * <pre>
 * java -jar harnex.jar --harnex.web.ui-dir=/path/to/frontend/dist
 * </pre>
 *
 * <p>
 * When {@code harnex.web.ui-dir} is unset (the default), this config does nothing and the backend stays API-only, so
 * the existing Supervisor / nginx / Vite-dev flows are completely unaffected.
 *
 * <p>
 * The front-end uses HTML5 history routing ({@code createWebHistory()}), so any non-asset path that doesn't map to a
 * real file is rewritten to {@code index.html} for client-side routing — except {@code /api}, {@code /actuator} and
 * {@code /sse}, which must keep returning a real 404 instead of an HTML page.
 */
@Slf4j
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Value("${harnex.web.ui-dir:}")
    private String uiDir;

    /**
     * @return the configured UI directory if it exists and contains a readable {@code index.html}, otherwise
     *         {@code null} (SPA serving disabled, backend stays API-only).
     */
    private Path resolvedUiDir() {
        if (uiDir == null || uiDir.isBlank()) {
            return null;
        }
        Path dir = Paths.get(uiDir).toAbsolutePath().normalize();
        if (!Files.isReadable(dir.resolve("index.html"))) {
            return null;
        }
        return dir;
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // The root path "/" is handled by Spring's welcome-page machinery (which looks in classpath static locations,
        // not our external ui-dir) and would 404 before reaching the resource resolver. Forward it to index.html so the
        // SPA loads at the origin root.
        if (resolvedUiDir() != null) {
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (uiDir == null || uiDir.isBlank()) {
            return;
        }
        Path dir = Paths.get(uiDir).toAbsolutePath().normalize();
        Path index = dir.resolve("index.html");
        if (!Files.isReadable(index)) {
            log.warn("harnex.web.ui-dir={} 不含可读的 index.html，跳过 SPA 静态托管（后端将保持纯 API）。", dir);
            return;
        }
        String location = dir.toUri().toString();
        if (!location.endsWith("/")) {
            location += "/";
        }
        registry.addResourceHandler("/**").addResourceLocations(location).resourceChain(true)
            .addResolver(new SpaResourceResolver());
        log.info("SPA 静态托管已启用：{} （history 路由回退至 index.html）", dir);
    }

    /**
     * Resolve a request to a real file when one exists, otherwise fall back to {@code index.html} so the SPA router can
     * handle the path. API / actuator / sse prefixes are never rewritten — they must surface a genuine 404.
     */
    static class SpaResourceResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.isReadable() && isRegularFile(requested)) {
                return requested;
            }
            if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")
                || resourcePath.startsWith("sse/")) {
                return null;
            }
            Resource index = location.createRelative("index.html");
            return index.isReadable() ? index : null;
        }

        private static boolean isRegularFile(Resource res) {
            try {
                return res.getFile().isFile();
            } catch (IOException e) {
                // Non-file resource (shouldn't happen for a file: location); treat as not servable.
                return false;
            }
        }
    }
}
