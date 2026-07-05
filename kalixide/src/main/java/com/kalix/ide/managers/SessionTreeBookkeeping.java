package com.kalix.ide.managers;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Session-keyed tree bookkeeping shared by the Run Manager and the optimisation
 * window. Both windows track CLI sessions in a {@code JTree} and used to
 * parallel-implement the same map cluster — sessionKey → tree node, display name,
 * last-seen status, completion timestamp — each with cleanup bugs the other had
 * already fixed. This class is that cluster, once: a dumb, EDT-confined holder
 * whose single {@link #remove} clears every map for a key so partial-removal
 * leaks are impossible by construction.
 *
 * <p>What deliberately does <em>not</em> live here: naming schemes (the Run
 * Manager numbers runs {@code Run_N} and stores names in {@link #namesView};
 * the optimisation window keeps the display name on {@code OptimisationInfo}),
 * status derivation, and any tree-model notification. Those genuinely differ
 * per window and stay with their owners.</p>
 *
 * @param <S> the per-window status enum (e.g. {@code DetailedRunStatus} or
 *            {@code OptimisationStatus})
 */
public final class SessionTreeBookkeeping<S> {

    private final Map<String, DefaultMutableTreeNode> sessionToNode = new HashMap<>();
    private final Map<String, String> sessionToName = new HashMap<>();
    private final Map<String, S> lastKnownStatus = new HashMap<>();
    private final Map<String, Long> completionTimestamps = new HashMap<>();

    // === Tree node ===

    /** Registers (or replaces) the tree node displayed for a session. */
    public void putNode(String sessionKey, DefaultMutableTreeNode node) {
        sessionToNode.put(sessionKey, node);
    }

    /** The tree node displayed for a session, or {@code null} if untracked. */
    public DefaultMutableTreeNode node(String sessionKey) {
        return sessionToNode.get(sessionKey);
    }

    /** Whether a tree node is tracked for the session. */
    public boolean hasNode(String sessionKey) {
        return sessionToNode.containsKey(sessionKey);
    }

    /** All tracked tree nodes. */
    public Collection<DefaultMutableTreeNode> nodes() {
        return sessionToNode.values();
    }

    /** Live (sessionKey → node) entries, for removal scans. */
    public Set<Map.Entry<String, DefaultMutableTreeNode>> nodeEntries() {
        return sessionToNode.entrySet();
    }

    // === Display name ===

    /** Sets the display name for a session. */
    public void putName(String sessionKey, String name) {
        sessionToName.put(sessionKey, name);
    }

    /** The display name for a session, or {@code null}. */
    public String name(String sessionKey) {
        return sessionToName.get(sessionKey);
    }

    /** All current display names (duplicate checks, name generation). */
    public Collection<String> names() {
        return sessionToName.values();
    }

    /**
     * The live sessionKey → display-name map. Exposed (not copied) for consumers
     * that mutate it directly, e.g. {@code RunContextMenuManager} removing an
     * entry when a run is removed via its context menu.
     */
    public Map<String, String> namesView() {
        return sessionToName;
    }

    // === Status ===

    /** Records the last-seen status for a session. */
    public void putStatus(String sessionKey, S status) {
        lastKnownStatus.put(sessionKey, status);
    }

    /** The last-seen status for a session, or {@code null}. */
    public S status(String sessionKey) {
        return lastKnownStatus.get(sessionKey);
    }

    // === Completion timestamps ===

    /** Records when a session's run completed. */
    public void putCompletionTimestamp(String sessionKey, long epochMillis) {
        completionTimestamps.put(sessionKey, epochMillis);
    }

    /** Clears the completion timestamp (session reused for a new run). */
    public void clearCompletionTimestamp(String sessionKey) {
        completionTimestamps.remove(sessionKey);
    }

    // === Removal ===

    /**
     * Removes every trace of a session — node, name, status, completion timestamp —
     * in one operation, returning the removed tree node (or {@code null}). This is
     * the whole point of the class: a future session-tracking fix lands here, once,
     * instead of in two windows' hand-rolled map clusters.
     */
    public DefaultMutableTreeNode remove(String sessionKey) {
        DefaultMutableTreeNode node = sessionToNode.remove(sessionKey);
        sessionToName.remove(sessionKey);
        lastKnownStatus.remove(sessionKey);
        completionTimestamps.remove(sessionKey);
        return node;
    }
}
