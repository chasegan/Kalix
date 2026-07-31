package com.kalix.ide.document;

/**
 * Writes model text back into an open model — the return path for the Optimiser's
 * "copy optimised model to main editor".
 *
 * <p>Separate from {@link OpenModel} on purpose: a source is read-only, so a window
 * that merely reads models cannot mutate one by accident. Writing back is an explicit
 * capability the host grants.</p>
 */
@FunctionalInterface
public interface ModelWriteBack {

    /**
     * Replaces {@code target}'s text and brings it to the front, marking it dirty.
     *
     * <p>The target may have been closed since the optimisation was created — that is
     * an ordinary outcome, not an error, so it is reported by return value and the
     * caller decides how to tell the user.</p>
     *
     * @param target the model to write into
     * @param text   the replacement text
     * @return {@code true} if written; {@code false} if the target is no longer open
     */
    boolean writeTo(OpenModel target, String text);
}
