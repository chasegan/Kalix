package com.kalix.ide.docking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

import static com.kalix.ide.docking.DockingConstants.*;

/**
 * A specialized JPanel that provides dockable functionality within the Kalix IDE docking system.
 *
 * <p>This panel can be made dockable by hovering over it and pressing F9. When activated,
 * it displays a translucent blue highlight border and a draggable grip in the top-left corner.
 * The panel can then be dragged to other docking areas or outside the window to create
 * floating windows.</p>
 *
 * <h3>Usage:</h3>
 * <ol>
 *   <li>Hover the mouse over the panel</li>
 *   <li>Press F9 to activate docking mode (shows highlight and grip)</li>
 *   <li>Click and drag the grip to move the panel</li>
 *   <li>Drop in a docking area or outside to create a floating window</li>
 * </ol>
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>Visual feedback with translucent blue highlighting</li>
 *   <li>Draggable grip with dotted pattern and shadow effects</li>
 *   <li>Automatic timeout after 6 seconds of inactivity</li>
 *   <li>Window-scoped F9 key binding that needs no keyboard focus of its own</li>
 *   <li>Integration with {@link DockingManager} for drag operations</li>
 * </ul>
 *
 * <p>The panel automatically manages its own mouse and keyboard listeners, and provides
 * visual feedback through theme-aware colors defined in {@link DockingConstants.Colors}.</p>
 *
 * @see DockingArea
 * @see DockingManager
 * @see DockingGrip
 * @author Kalix Development Team
 * @since 2025-09-27
 */
public class DockablePanel extends JPanel {

    /** ActionMap key for the F9 docking-mode toggle. */
    private static final String ACTION_TOGGLE_DOCKING = "toggleDockingMode";

    // State management
    private boolean isHighlighted = false;
    private boolean isHovered = false;
    private boolean dockingModeActive = false;
    private Timer dockingModeTimer;
    private DockingGrip grip;

    // Listeners
    private MouseListener hoverListener;

    /**
     * Creates a new DockablePanel with BorderLayout as the default layout manager.
     */
    public DockablePanel() {
        this(new BorderLayout());
    }

    /**
     * Creates a new DockablePanel with the specified layout manager.
     *
     * @param layout the layout manager to use for this panel
     */
    public DockablePanel(LayoutManager layout) {
        super(layout);
        initializeDocking();
    }

    /**
     * Initializes the docking functionality for this panel by setting up hover
     * detection, the F9 key binding, and the grip component.
     */
    private void initializeDocking() {
        // Create grip component
        grip = new DockingGrip(this);
        grip.setVisible(false);

        // Hover detection. Mouse events do not require keyboard focus, so the
        // panel tracks hover without ever stealing focus from its content.
        hoverListener = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                isHovered = true;
                updateHighlight();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                isHovered = false;
                updateHighlight();
            }
        };
        addMouseListener(hoverListener);

        // F9 toggles docking mode. It is registered as a window-scoped key
        // binding (WHEN_IN_FOCUSED_WINDOW) rather than a KeyListener so it fires
        // regardless of which component holds keyboard focus - the panel never
        // needs focus of its own, and so never steals it from its content.
        //
        // The action is enabled only while the panel is hovered. With several
        // dockable panels in one window each registers this keystroke; Swing
        // dispatches to them in turn and skips any whose action is disabled, so
        // gating on enablement (rather than inside actionPerformed) ensures the
        // keystroke falls through to the panel actually under the mouse.
        InputMap inputMap = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = getActionMap();
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0), ACTION_TOGGLE_DOCKING);
        actionMap.put(ACTION_TOGGLE_DOCKING, new AbstractAction() {
            @Override
            public boolean isEnabled() {
                return isHovered;
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                toggleDockingMode();
            }
        });
    }

    /**
     * Forwards mouse enter/exit events from a child component to this panel's
     * hover listeners.
     *
     * <p>A child component that fills the panel intercepts the mouse events that
     * would otherwise let the panel detect hover. Subclasses call this for each
     * such child so docking-mode hover highlighting keeps working.</p>
     *
     * @param child the child component whose hover events should be forwarded
     */
    protected void forwardHoverEvents(Component child) {
        child.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                for (MouseListener listener : getMouseListeners()) {
                    listener.mouseEntered(e);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                for (MouseListener listener : getMouseListeners()) {
                    listener.mouseExited(e);
                }
            }
        });
    }

    /**
     * Toggles docking mode on/off with F9.
     */
    private void toggleDockingMode() {
        if (dockingModeActive) {
            deactivateDockingMode();
        } else {
            activateDockingMode();
        }
    }

    /**
     * Activates docking mode, showing the grip if hovered.
     * Mode automatically times out after 6 seconds unless user interacts with it.
     */
    private void activateDockingMode() {
        if (dockingModeActive) {
            return; // Already active
        }

        dockingModeActive = true;
        updateHighlight();

        // Start timeout timer (6 seconds)
        startDockingModeTimer();
    }

    /**
     * Deactivates docking mode, hiding the grip.
     */
    private void deactivateDockingMode() {
        if (!dockingModeActive) {
            return; // Already inactive
        }

        dockingModeActive = false;
        updateHighlight();

        // Clean up any timer if it exists
        if (dockingModeTimer != null) {
            dockingModeTimer.stop();
            dockingModeTimer = null;
        }

        // Docking mode deactivated
    }

    /**
     * Updates the highlight state based on hover and docking mode state.
     */
    private void updateHighlight() {
        boolean shouldHighlight = isHovered && dockingModeActive;

        if (shouldHighlight != isHighlighted) {
            isHighlighted = shouldHighlight;
            grip.setVisible(isHighlighted);
            repaint();

            if (isHighlighted) {
                positionGrip();
            }
        }
    }

    /**
     * Positions the grip in the top-left corner of the panel.
     */
    private void positionGrip() {
        if (grip.getParent() != this) {
            add(grip);
        }

        grip.setBounds(Dimensions.GRIP_MARGIN, Dimensions.GRIP_MARGIN,
                       Dimensions.GRIP_WIDTH, Dimensions.GRIP_HEIGHT);
        setComponentZOrder(grip, 0); // Bring to front
    }

    @Override
    public void doLayout() {
        super.doLayout();
        // Ensure grip stays positioned correctly after layout
        if (grip != null && grip.isVisible()) {
            grip.setBounds(Dimensions.GRIP_MARGIN, Dimensions.GRIP_MARGIN,
                       Dimensions.GRIP_WIDTH, Dimensions.GRIP_HEIGHT);
            setComponentZOrder(grip, 0);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (isHighlighted) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Draw highlight border
            g2d.setColor(Colors.HIGHLIGHT);
            g2d.setStroke(new BasicStroke(Dimensions.HIGHLIGHT_BORDER_WIDTH));
            g2d.drawRect(1, 1, getWidth() - 3, getHeight() - 3);

            g2d.dispose();
        }
    }

    /**
     * Starts the docking mode timeout timer.
     */
    private void startDockingModeTimer() {
        // Stop any existing timer
        if (dockingModeTimer != null) {
            dockingModeTimer.stop();
        }

        // Create new timer that deactivates docking mode after timeout
        dockingModeTimer = new Timer(Timing.DOCKING_MODE_TIMEOUT, e -> {
            deactivateDockingMode();
        });
        dockingModeTimer.setRepeats(false); // Only fire once
        dockingModeTimer.start();
    }

    /**
     * Cancels docking mode due to user interaction.
     * Called when user starts dragging or completes a docking operation.
     */
    public void cancelDockingMode() {
        if (dockingModeActive) {
            deactivateDockingMode();
        }
    }
}