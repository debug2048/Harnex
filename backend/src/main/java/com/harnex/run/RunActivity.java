package com.harnex.run;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import com.harnex.execution.ExecutionSpec;

/** Root reservations exist in every profile, independently of Agent contexts and project services. */
@Component
public final class RunActivity {
    private final Map<String, ExecutionSpec.Kind> reservations = new ConcurrentHashMap<>();
    private final List<RunLifecycleParticipant> participants;

    public RunActivity(List<RunLifecycleParticipant> participants) { this.participants = List.copyOf(participants); }

    public synchronized boolean tryReserve(String id, ExecutionSpec.Kind kind) {
        if (id == null || kind == null || isLive(id)) return false;
        reservations.put(id, kind);
        List<RunLifecycleParticipant> acquired = new ArrayList<>();
        try {
            for (var participant : participants) {
                if (!participant.supports(kind)) continue;
                if (!participant.tryReserve(id)) {
                    acquired.forEach(p -> p.release(id));
                    reservations.remove(id);
                    return false;
                }
                acquired.add(participant);
            }
            return true;
        } catch (RuntimeException failure) {
            acquired.forEach(p -> p.release(id));
            reservations.remove(id);
            throw failure;
        }
    }

    public synchronized void releaseReservation(String id) {
        var kind = reservations.get(id);
        if (kind == null) return;
        try { participants.stream().filter(p -> p.supports(kind)).forEach(p -> p.release(id)); }
        finally { reservations.remove(id); }
    }

    public boolean cancel(String id) {
        return cancel(id, reservations.get(id));
    }

    public boolean cancel(String id, ExecutionSpec.Kind kind) {
        boolean signalled = reservations.containsKey(id);
        for (var participant : participants) {
            if (kind == null || participant.supports(kind)) signalled |= participant.cancel(id);
        }
        return signalled;
    }

    public void interruptExecution(String id) {
        var kind = reservations.get(id);
        participants.stream().filter(p -> kind == null || p.supports(kind)).forEach(p -> p.interrupt(id));
    }

    public boolean isLive(String id) { return id != null && activeConversationIds().contains(id); }
    public Set<String> activeConversationIds() {
        Set<String> ids = new HashSet<>(reservations.keySet());
        participants.forEach(p -> ids.addAll(p.activeConversationIds()));
        return Set.copyOf(ids);
    }
    public int activeCount() { return activeConversationIds().size(); }
    public String deletionRejection(String id) {
        return participants.stream().map(p -> p.deletionRejection(id)).filter(Objects::nonNull).findFirst().orElse(null);
    }
}
