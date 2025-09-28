package com.kalix.ide.docking;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Point;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import static com.kalix.ide.docking.DockingConstants.*;

/**
 * A floating window that hosts dockable panels outside the main application window.
 *
 * <p>DockingWindow provides a separate window container for {@link DockablePanel} instances
 * that have been dragged outside of the main application. These windows can be moved
 * independently and serve as floating containers for panels.</p>
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>Contains a main {@link DockingArea} for accepting dropped panels</li>
 *   <li>Optional auto-close behavior when empty</li>
 *   <li>Automatic registration with {@link DockingManager}</li>
 *   <li>Window lifecycle management and cleanup</li>
 *   <li>Icon inheritance from main application window</li>
 * </ul>
 *
 * <h3>Auto-Close Behavior:</h3>
 * <p>Windows created through drag operations typically have auto-close enabled,
 * meaning they will automatically close when they become empty. Windows created
 * manually may have this behavior disabled to persist even when empty.</p>
 *
 * @see DockablePanel
 * @see DockingArea
 * @see DockingManager
 * @author Kalix Development Team
 * @since 2025-09-27
 */
public class DockingWindow extends JFrame {

    private DockingArea mainArea;
    private static int windowCounter = 1;
    private boolean autoClose = false; // Whether to auto-close when empty

    /**
     * Creates a new DockingWindow with a default auto-generated title.
     */
    public DockingWindow() {
        this(Text.WINDOW_TITLE_PREFIX + " " + windowCounter++);
    }

    /**
     * Creates a new DockingWindow with the specified title and auto-close disabled.
     *
     * @param title the window title
     */
    public DockingWindow(String title) {
        this(title, false);
    }

    /**
     * Creates a new DockingWindow with the specified title and auto-close behavior.
     *
     * @param title the window title
     * @param autoClose if true, the window will automatically close when empty
     */
    public DockingWindow(String title, boolean autoClose) {
        super(title);
        this.autoClose = autoClose;
        initializeWindow();
    }

    /**
     * Initializes the window with basic properties and layout.
     */
    private void initializeWindow() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(Dimensions.DEFAULT_WINDOW_WIDTH, Dimensions.DEFAULT_WINDOW_HEIGHT);

        // Create main docking area
        mainArea = new DockingArea(Layout.MAIN_AREA_NAME);
        add(mainArea, BorderLayout.CENTER);

        // Handle window closing
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleWindowClosing();
            }
        });

        // Register with DockingManager
        DockingManager.getInstance().registerDockingWindow(this);

        // Set window icon (if available)
        try {
            // Try to use the same icon as the main application
            Frame[] frames = Frame.getFrames();
            for (Frame frame : frames) {
                if (frame.getIconImage() != null && frame != this) {
                    setIconImage(frame.getIconImage());
                    break;
                }
            }
        } catch (Exception e) {
            // Ignore icon loading errors
        }
    }

    /**
     * Adds a dockable panel to this window.
     */
    public void addDockablePanel(DockablePanel panel) {
        mainArea.addDockablePanel(panel);

        // Update window title based on panel content
        updateWindowTitle();

        // Ensure window is visible and properly sized
        if (!isVisible()) {
            pack();
            setLocationRelativeTo(null);
        }
    }

    /**
     * Removes a dockable panel from this window.
     */
    public void removeDockablePanel(DockablePanel panel) {
        mainArea.removeDockablePanel(panel);
        updateWindowTitle();

        // Auto-close logic is now handled by DockingManager to prevent race conditions
    }

    /**
     * Returns the main docking area of this window.
     */
    public DockingArea getMainArea() {
        return mainArea;
    }

    /**
     * Returns whether this window contains any docked panels.
     */
    public boolean isEmpty() {
        return mainArea.isEmpty();
    }

    /**
     * Updates the window title based on current content.
     */
    private void updateWindowTitle() {
        if (isEmpty()) {
            setTitle(Text.EMPTY_WINDOW_TITLE);
        } else {
            Component panel = mainArea.getComponent(0);
            if (panel instanceof DockablePanel) {
                String panelName = panel.getClass().getSimpleName();
                setTitle(Text.WINDOW_TITLE_PREFIX + " - " + panelName);
            } else {
                setTitle(Text.WINDOW_TITLE_PREFIX + " - Content");
            }
        }
    }

    /**
     * Handles the window closing event.
     */
    private void handleWindowClosing() {
        if (!isEmpty()) {
            int result = JOptionPane.showConfirmDialog(
                this,
                "This window contains docked panels. Close anyway?",
                "Close Docking Window",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            );

            if (result != JOptionPane.YES_OPTION) {
                return;
            }
        }

        closeWindow();
    }

    /**
     * Closes the window and performs cleanup.
     */
    public void closeWindow() {
        // Unregister from DockingManager
        DockingManager.getInstance().unregisterDockingWindow(this);

        // Dispose of the window
        dispose();
    }

    /**
     * Factory method to create a new docking window with a panel.
     * Creates a persistent window (does not auto-close when empty).
     */
    public static DockingWindow createWithPanel(DockablePanel panel) {
        DockingWindow window = new DockingWindow();
        window.addDockablePanel(panel);
        return window;
    }

    /**
     * Factory method to create an auto-closing window with a panel already added.
     * The window will automatically close when it becomes empty.
     */
    public static DockingWindow createAutoClosingWithPanel(DockablePanel panel) {
        DockingWindow window = new DockingWindow(Text.FLOATING_WINDOW_PREFIX + " " + windowCounter++, true);
        window.addDockablePanel(panel);
        return window;
    }

    /**
     * Returns whether this window will auto-close when empty.
     */
    public boolean isAutoClose() {
        return autoClose;
    }

    /**
     * Sets whether this window should auto-close when empty.
     */
    public void setAutoClose(boolean autoClose) {
        this.autoClose = autoClose;
    }

    /**
     * Factory method to create a new docking window at a specific location.
     */
    public static DockingWindow createAt(Point location) {
        DockingWindow window = new DockingWindow();
        window.setLocation(location);
        return window;
    }
}