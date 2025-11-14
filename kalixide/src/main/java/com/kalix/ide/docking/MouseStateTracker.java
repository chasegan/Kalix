package com.kalix.ide.docking;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;

import static com.kalix.ide.docking.DockingConstants.*;

/**
 * Tracks global mouse button state to detect when mouse buttons are released
 * without receiving the corresponding mouseReleased events.
 * This is particularly useful for handling Mac trackpad issues.
 */
public class MouseStateTracker {
    private volatile boolean mouseButtonPressed = false;
    private Timer mouseStateTimer;
    private Runnable buttonReleasedCallback;

    /**
     * Creates a new MouseStateTracker and sets up global mouse event listening.
     */
    public MouseStateTracker() {
        setupGlobalMouseListener();
    }

    /**
     * Sets up a global mouse listener to track mouse button state across the entire application.
     */
    private void setupGlobalMouseListener() {
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event instanceof MouseEvent mouseEvent) {

                if (mouseEvent.getID() == MouseEvent.MOUSE_PRESSED &&
                    SwingUtilities.isLeftMouseButton(mouseEvent)) {
                    mouseButtonPressed = true;
                } else if (mouseEvent.getID() == MouseEvent.MOUSE_RELEASED &&
                          SwingUtilities.isLeftMouseButton(mouseEvent)) {
                    mouseButtonPressed = false;
                }
            }
        }, AWTEvent.MOUSE_EVENT_MASK);
    }

    /**
     * Starts monitoring for mouse button release with the given callback.
     *
     * @param callback Runnable to execute when button release is detected
     */
    public void startMonitoring(Runnable callback) {
        this.buttonReleasedCallback = callback;

        // Stop existing timer if running
        if (mouseStateTimer != null) {
            mouseStateTimer.stop();
        }

        // Create repeating timer that checks button state
        mouseStateTimer = new Timer(Timing.MOUSE_POLLING_INTERVAL, e -> {
            if (!mouseButtonPressed && buttonReleasedCallback != null) {
                // Mouse button has been released - trigger callback
                SwingUtilities.invokeLater(buttonReleasedCallback);
            }
        });
        mouseStateTimer.setRepeats(true);
        mouseStateTimer.start();
    }

    /**
     * Stops monitoring mouse button state and cleans up resources.
     */
    public void stopMonitoring() {
        if (mouseStateTimer != null) {
            mouseStateTimer.stop();
            mouseStateTimer = null;
        }
        buttonReleasedCallback = null;
    }
}