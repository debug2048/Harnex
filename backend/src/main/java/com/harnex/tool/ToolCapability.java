package com.harnex.tool;

/**
 * Orthogonal tool-behaviour declarations. A tool reports its capabilities via {@link Tool#capabilities()} and every
 * runtime gate (plan-mode admission, concurrency batching, approval, UI badges, provider {@code x-capabilities} schema)
 * reads the same Set &mdash; no more half-overlapping {@code isReadOnly / isDestructive / isConcurrencySafe /
 * isAllowedInPlanMode} booleans whose interactions had to be memorised on a per-call-site basis.
 *
 * <h2>Capability matrix</h2>
 * <ul>
 * <li>{@link #READS_FILESYSTEM} &mdash; tool may open / stat / read local files.</li>
 * <li>{@link #WRITES_FILESYSTEM} &mdash; tool may create / mutate / delete local files.</li>
 * <li>{@link #READS_NETWORK} &mdash; tool may perform outbound HTTP / DNS / MCP requests.</li>
 * <li>{@link #WRITES_NETWORK} &mdash; tool may perform outbound writes (POST / PUT / DELETE, email, paid LLM
 * round-trips, etc.) whose side effects persist remotely.</li>
 * <li>{@link #MUTATES_THREAD_STATE} &mdash; tool writes to per-thread state (todo list, plan-mode flag, sub-agent
 * registration) but produces no external side effects.</li>
 * <li>{@link #SPAWNS_PROCESS} &mdash; tool launches an OS child process.</li>
 * <li>{@link #DESTRUCTIVE_IRREVERSIBLE} &mdash; tool's side effects cannot be trivially undone (file_write overwrite,
 * bash rm, memory_forget). Triggers the approval prompt and the destructive-tool badge.</li>
 * <li>{@link #PLAN_MODE_SAFE} &mdash; tool may run while the thread is in plan-mode. Almost always satisfied by tools
 * that don't mutate the world &mdash; with the deliberate exceptions of {@code todo_write} and {@code submit_plan}
 * which ARE the plan-mode workflow and must be allowed even though they mutate thread state.</li>
 * <li>{@link #APPROVAL_FREE} &mdash; tool is a pure interaction/control primitive whose own execution is the user
 * approval/feedback channel, or only mutates local thread control state. The generic approval gate skips these tools so
 * {@code ask_user}, {@code submit_plan}, and {@code update_goal} do not require a second approval before doing their
 * intended blocking/control work.</li>
 * <li>{@link #CONCURRENCY_SAFE} &mdash; multiple invocations of this tool may run in parallel within one agent turn.
 * Conservative default is OFF (a single bash racing against itself is rarely what the user wants); pure read-only /
 * network-fetch tools opt in explicitly.</li>
 * <li>{@link #ORCHESTRATES_AGENT} &mdash; the call waits for another agent loop. These calls use a coordination pool
 * instead of the ordinary tool worker pool so parents waiting on children cannot starve those children of workers.</li>
 * </ul>
 */
public enum ToolCapability {
    READS_FILESYSTEM, WRITES_FILESYSTEM, READS_NETWORK, WRITES_NETWORK, MUTATES_THREAD_STATE, SPAWNS_PROCESS,
    DESTRUCTIVE_IRREVERSIBLE, PLAN_MODE_SAFE, APPROVAL_FREE, CONCURRENCY_SAFE, ORCHESTRATES_AGENT
}
