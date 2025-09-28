package com.kalix.ide.docking;

import javax.swing.Timer;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
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
    private Point lastDragPosition;

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
     * @param dragPosition The current drag position to pass to callback
     * @param callback Runnable to execute when button release is detected
     */
    public void startMonitoring(Point dragPosition, Runnable callback) {
        this.lastDragPosition = new Point(dragPosition);
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
     * Updates the current drag position for the callback.
     *
     * @param dragPosition The new drag position
     */
    public void updateDragPosition(Point dragPosition) {
        this.lastDragPosition = new Point(dragPosition);
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
        lastDragPosition = null;
    }

    /**
     * Returns whether the left mouse button is currently pressed according to global state.
     *
     * @return true if mouse button is pressed, false otherwise
     */
    public boolean isMouseButtonPressed() {
        return mouseButtonPressed;
    }

    /**
     * Returns the last known drag position.
     *
     * @return Point representing last drag position, or null if not set
     */
    public Point getLastDragPosition() {
        return lastDragPosition != null ? new Point(lastDragPosition) : null;
    }
}