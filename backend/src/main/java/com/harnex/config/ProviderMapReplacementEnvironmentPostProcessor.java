package com.harnex.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Gives {@code harnex.providers} collection ownership to the highest-precedence configuration document that declares
 * it. Provider profiles and all non-provider settings retain ordinary Spring layering; only concrete Provider entries
 * are replaced as one user-owned collection.
 */
public final class ProviderMapReplacementEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

    static final String PROVIDERS_ROOT = "harnex.providers";

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        replaceLowerPrecedenceProviderMaps(environment.getPropertySources());
    }

    static void replaceLowerPrecedenceProviderMaps(MutablePropertySources propertySources) {
        boolean ownerFound = false;
        List<String> lowerSourcesToFilter = new ArrayList<>();
        for (PropertySource<?> propertySource : propertySources) {
            if (!hasProviderProperties(propertySource)) continue;
            if (!ownerFound && propertySource instanceof OriginTrackedMapPropertySource) {
                ownerFound = true;
                continue;
            }
            if (ownerFound) lowerSourcesToFilter.add(propertySource.getName());
        }
        for (String sourceName : lowerSourcesToFilter) {
            PropertySource<?> source = propertySources.get(sourceName);
            if (source instanceof EnumerablePropertySource<?> enumerable
                && !(source instanceof ProviderFilteringPropertySource)) {
                propertySources.replace(sourceName, new ProviderFilteringPropertySource(enumerable));
            }
        }
    }

    /** A reload reapplies collection ownership to a private source list without altering the startup Environment. */
    static PropertySource<?> unfiltered(PropertySource<?> source) {
        return source instanceof ProviderFilteringPropertySource filtered ? filtered.getSource() : source;
    }

    private static boolean hasProviderProperties(PropertySource<?> propertySource) {
        if (!(propertySource instanceof EnumerablePropertySource<?> enumerable)) return false;
        return Arrays.stream(enumerable.getPropertyNames())
            .anyMatch(ProviderMapReplacementEnvironmentPostProcessor::isProviderProperty);
    }

    private static boolean isProviderProperty(String name) {
        return PROVIDERS_ROOT.equals(name)
            || name.startsWith(PROVIDERS_ROOT + ".")
            || name.startsWith(PROVIDERS_ROOT + "[");
    }

    private static final class ProviderFilteringPropertySource
        extends EnumerablePropertySource<EnumerablePropertySource<?>> implements OriginLookup<String> {

        private ProviderFilteringPropertySource(EnumerablePropertySource<?> delegate) {
            super(delegate.getName(), delegate);
        }

        @Override
        public Object getProperty(String name) {
            return isProviderProperty(name) ? null : source.getProperty(name);
        }

        @Override
        public String[] getPropertyNames() {
            return Arrays.stream(source.getPropertyNames())
                .filter(name -> !isProviderProperty(name)).toArray(String[]::new);
        }

        @Override
        public Origin getOrigin(String key) {
            return OriginLookup.getOrigin(source, key);
        }

        @Override
        public boolean isImmutable() {
            return source instanceof OriginLookup<?> lookup && lookup.isImmutable();
        }
    }
}
