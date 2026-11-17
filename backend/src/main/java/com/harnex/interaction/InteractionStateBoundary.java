package com.harnex.interaction;

import java.util.function.Supplier;

/** Short state transitions only: never hold this boundary while awaiting a human or doing provider work. */
public interface InteractionStateBoundary {
    <T> T update(String conversationId, String kind, Supplier<T> mutation);
    InteractionStateBoundary DIRECT = new InteractionStateBoundary() {
        @Override public <T> T update(String conversationId, String kind, Supplier<T> mutation) { return mutation.get(); }
    };
}
