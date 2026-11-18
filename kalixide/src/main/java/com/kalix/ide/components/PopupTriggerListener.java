package com.kalix.ide.components;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Runs an action on the platform's context-menu gesture. The popup trigger arrives on press
 * on macOS and Linux and on release on Windows, so both events are checked. The check is
 * {@link MouseEvent#isPopupTrigger()}, not the right button, because on macOS a Ctrl+click is
 * a trigger too.
 */
public final class PopupTriggerListener extends MouseAdapter {

    private final Consumer<MouseEvent> onPopupTrigger;

    public PopupTriggerListener(Consumer<MouseEvent> onPopupTrigger) {
        this.onPopupTrigger = onPopupTrigger;
    }

    @Override
    public void mousePressed(MouseEvent e) {
        fireIfPopupTrigger(e);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        fireIfPopupTrigger(e);
    }

    private void fireIfPopupTrigger(MouseEvent e) {
        if (e.isPopupTrigger()) {
            onPopupTrigger.accept(e);
        }
    }
}
