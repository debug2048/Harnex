package com.harnex.model;

import java.util.List;

/** Derived continuity state, never a second persisted conversation history. */
public record HumanTurnContext(String original, String anchor, List<String> contextTurns, String latest, int turnCount) {
    public HumanTurnContext { contextTurns = List.copyOf(contextTurns); }
}
