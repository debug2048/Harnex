package com.harnex.config;

import java.lang.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Components which require a project, its private Agent state or workspace authority. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "harnex", name = "runtime-profile", havingValue = "PROJECT", matchIfMissing = true)
public @interface ProjectRuntime {}
