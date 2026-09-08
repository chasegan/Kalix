package com.kalix.ide.managers;

import org.junit.jupiter.api.Test;

import javax.swing.tree.DefaultMutableTreeNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins {@link SessionTreeBookkeeping#latestCompletedSession()}, the runner-up
 * selection the "Last" alias self-heal relies on: most recent completion wins,
 * removed sessions stop being candidates, never-completed sessions never win.
 */
class SessionTreeBookkeepingTest {

    private static SessionTreeBookkeeping<String> tracked(String... keysAndTimes) {
        SessionTreeBookkeeping<String> b = new SessionTreeBookkeeping<>();
        for (int i = 0; i < keysAndTimes.length; i += 2) {
            b.putNode(keysAndTimes[i], new DefaultMutableTreeNode(keysAndTimes[i]));
            b.putCompletionTimestamp(keysAndTimes[i], Long.parseLong(keysAndTimes[i + 1]));
        }
        return b;
    }

    @Test
    void latestCompletionWins() {
        SessionTreeBookkeeping<String> b = tracked("a", "100", "b", "300", "c", "200");
        assertEquals("b", b.latestCompletedSession());
        assertEquals(300L, b.completionTimestamp("b"));
    }

    @Test
    void removedSessionsStopBeingCandidates() {
        SessionTreeBookkeeping<String> b = tracked("a", "100", "b", "300");
        b.remove("b"); // the current Last leaves: survivor must win
        assertEquals("a", b.latestCompletedSession());
        assertNull(b.completionTimestamp("b"));
    }

    @Test
    void neverCompletedSessionsNeverWin() {
        SessionTreeBookkeeping<String> b = new SessionTreeBookkeeping<>();
        b.putNode("running", new DefaultMutableTreeNode("running"));
        // no completion timestamp recorded for "running"
        assertNull(b.latestCompletedSession(),
            "a session that never completed cannot become Last");

        b.putCompletionTimestamp("done", 50L);
        b.putNode("done", new DefaultMutableTreeNode("done"));
        assertEquals("done", b.latestCompletedSession());
    }

    @Test
    void emptyBookkeepingYieldsNull() {
        assertNull(new SessionTreeBookkeeping<String>().latestCompletedSession());
    }
}
