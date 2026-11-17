package com.harnex.image;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import com.harnex.execution.ImageSpec;
import com.harnex.execution.RunExecutionContext;
import com.harnex.net.HttpExchangeState;

/** Image protocol port. Providers stream binary into durable staging, never into an event or tool result. */
public interface ImageProvider {
    String profile();
    Output generate(Input input, RunExecutionContext context, HttpExchangeState exchange, ResultSink sink);
    record Input(ImageServiceSnapshot service, ImageServiceRegistry.Connection connection, ImageSpec spec, String prompt) {}
    record Output(String providerHandle, Map<String, Long> usage, boolean complete) {
        public Output { usage = usage == null ? Map.of() : Map.copyOf(usage); }
    }
    @FunctionalInterface interface BinaryWriter { void write(OutputStream output) throws IOException; }
    @FunctionalInterface interface ResultSink { void receive(int index, BinaryWriter writer) throws IOException; }
}
