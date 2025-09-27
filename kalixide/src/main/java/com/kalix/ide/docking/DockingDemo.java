package com.kalix.ide.docking;

import com.kalix.ide.KalixIDE;
import com.kalix.ide.constants.AppConstants;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

/**
 * Demonstration class showing how to integrate the docking system with KalixIDE.
 * This creates a modified version of the main window using dockable panels.
 */
public class DockingDemo extends JFrame {

    private DockableMapPanel dockableMapPanel;
    private DockableTextEditor dockableTextEditor;
    private JSplitPane splitPane;

    public DockingDemo() {
        initializeDemo();
    }

    /**
     * Initializes the docking demonstration window.
     */
    private void initializeDemo() {
        setTitle("Kalix IDE - Docking System Demo");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);

        // Initialize docking context with common services
        initializeDockingContext();

        // Create dockable components
        createDockableComponents();

        // Set up layout with docking areas
        setupLayout();

        // Add menu for testing
        setupMenuBar();

        // Note: No global key handling needed - DockablePanel handles its own key events

        setVisible(true);
    }

    /**
     * Initializes the docking context with common services and actions.
     */
    private void initializeDockingContext() {
        DockingContext context = DockingContext.getInstance();

        // Register common actions that docked panels might want to access
        context.registerAction("zoom_in", new AbstractAction("Zoom In") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (dockableMapPanel != null) {
                    dockableMapPanel.zoomIn();
                }
            }
        });

        context.registerAction("zoom_out", new AbstractAction("Zoom Out") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (dockableMapPanel != null) {
                    dockableMapPanel.zoomOut();
                }
            }
        });

        context.registerAction("save", new AbstractAction("Save") {
            @Override
            public void actionPerformed(ActionEvent e) {
                JOptionPane.showMessageDialog(DockingDemo.this, "Save action triggered from docked panel!");
            }
        });

        // Register status update service
        context.registerService("statusUpdater", (Runnable) () ->
            System.out.println("Status updated from docked panel"));
    }

    /**
     * Creates the dockable components.
     */
    private void createDockableComponents() {
        dockableMapPanel = new DockableMapPanel();
        dockableTextEditor = new DockableTextEditor();

        // Set some initial content in the text editor
        dockableTextEditor.setText(AppConstants.DEFAULT_MODEL_TEXT);
    }

    /**
     * Sets up the layout using docking areas.
     */
    private void setupLayout() {
        setLayout(new BorderLayout());

        // Create left and right docking areas
        DockingArea leftArea = new DockingArea("Left Panel");
        DockingArea rightArea = new DockingArea("Right Panel");

        // Add the dockable panels to their respective areas
        leftArea.addDockablePanel(dockableMapPanel);
        rightArea.addDockablePanel(dockableTextEditor);

        // Create split pane with docking areas
        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftArea, rightArea);
        splitPane.setDividerLocation(500);
        splitPane.setResizeWeight(0.4);

        add(splitPane, BorderLayout.CENTER);

        // Add status bar
        JLabel statusLabel = new JLabel("Docking Demo Ready - Hover over panels and press F9 to activate docking");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        add(statusLabel, BorderLayout.SOUTH);
    }

    /**
     * Sets up a simple menu bar for testing docking functionality.
     */
    private void setupMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // File menu
        JMenu fileMenu = new JMenu("File");
        fileMenu.add(new AbstractAction("New Floating Map Panel") {
            @Override
            public void actionPerformed(ActionEvent e) {
                createFloatingMapPanel();
            }
        });
        fileMenu.add(new AbstractAction("New Floating Text Editor") {
            @Override
            public void actionPerformed(ActionEvent e) {
                createFloatingTextEditor();
            }
        });
        fileMenu.addSeparator();
        fileMenu.add(new AbstractAction("Exit") {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.exit(0);
            }
        });

        // View menu
        JMenu viewMenu = new JMenu("View");
        viewMenu.add(new AbstractAction("Zoom In") {
            @Override
            public void actionPerformed(ActionEvent e) {
                DockingContext.getInstance().invokeAction("zoom_in");
            }
        });
        viewMenu.add(new AbstractAction("Zoom Out") {
            @Override
            public void actionPerformed(ActionEvent e) {
                DockingContext.getInstance().invokeAction("zoom_out");
            }
        });

        // Help menu
        JMenu helpMenu = new JMenu("Help");
        helpMenu.add(new AbstractAction("About Docking System") {
            @Override
            public void actionPerformed(ActionEvent e) {
                showAboutDialog();
            }
        });

        menuBar.add(fileMenu);
        menuBar.add(viewMenu);
        menuBar.add(helpMenu);

        setJMenuBar(menuBar);
    }


    /**
     * Creates a new floating map panel for testing.
     */
    private void createFloatingMapPanel() {
        DockableMapPanel floatingMapPanel = new DockableMapPanel();
        DockingWindow window = DockingWindow.createWithPanel(floatingMapPanel);
        window.setTitle("Floating Map Panel");
        window.setLocation(getX() + 50, getY() + 50);
        window.setVisible(true);
    }

    /**
     * Creates a new floating text editor for testing.
     */
    private void createFloatingTextEditor() {
        DockableTextEditor floatingTextEditor = new DockableTextEditor();
        floatingTextEditor.setText("# This is a floating text editor\n\nYou can dock this panel by:\n1. Hovering over it\n2. Pressing F6\n3. Dragging the grip that appears");

        DockingWindow window = DockingWindow.createWithPanel(floatingTextEditor);
        window.setTitle("Floating Text Editor");
        window.setLocation(getX() + 100, getY() + 100);
        window.setVisible(true);
    }

    /**
     * Shows information about the docking system.
     */
    private void showAboutDialog() {
        String message = """
                Kalix IDE Docking System Demo

                How to use:
                1. Hover your mouse over any panel
                2. Press and hold F9
                3. Notice the blue highlight and drag grip
                4. Click and drag the grip to undock the panel
                5. Drop it in another docking area or outside to create a floating window

                Features:
                • Translucent blue highlighting
                • Dotted grip with inset shadow effect
                • Drag and drop between areas
                • Automatic floating window creation
                • Panel-to-window communication via DockingContext
                """;

        JOptionPane.showMessageDialog(this, message, "Docking System Demo", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Main method to run the docking demonstration.
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new DockingDemo();
        });
    }
}