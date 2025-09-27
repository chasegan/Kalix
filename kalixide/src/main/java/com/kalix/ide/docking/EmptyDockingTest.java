package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

/**
 * Test with simple empty DockablePanel to isolate key handling issues.
 */
public class EmptyDockingTest extends JFrame {

    public EmptyDockingTest() {
        setTitle("Empty Docking Test - Pure DockablePanel");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);

        setupUI();
        setVisible(true);
    }

    private void setupUI() {
        setLayout(new BorderLayout());

        // Create a simple DockablePanel with just basic content (no complex children)
        DockablePanel emptyPanel = new DockablePanel();
        emptyPanel.setBackground(Color.LIGHT_GRAY);

        // Add simple content that won't interfere with key events
        emptyPanel.setLayout(new BorderLayout());
        JLabel instructions = new JLabel(
            "<html><center>" +
            "<h2>Empty DockablePanel Test</h2>" +
            "<p>Hover here and press F9</p>" +
            "<p>Should show blue highlight + grip</p>" +
            "</center></html>",
            SwingConstants.CENTER
        );
        instructions.setFont(instructions.getFont().deriveFont(16f));
        emptyPanel.add(instructions, BorderLayout.CENTER);

        // Put it in a docking area
        DockingArea mainArea = new DockingArea("Main Area");
        mainArea.addDockablePanel(emptyPanel);

        add(mainArea, BorderLayout.CENTER);

        // Add menu for testing
        setupMenu();

        // Status
        JLabel status = new JLabel("Test: Hover over gray panel and press F9");
        status.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(status, BorderLayout.SOUTH);
    }

    private void setupMenu() {
        JMenuBar menuBar = new JMenuBar();

        JMenu testMenu = new JMenu("Test");

        testMenu.add(new AbstractAction("Create New Empty Floating Panel") {
            @Override
            public void actionPerformed(ActionEvent e) {
                createFloatingEmptyPanel();
            }
        });

        testMenu.add(new AbstractAction("Create Multiple Empty Panels") {
            @Override
            public void actionPerformed(ActionEvent e) {
                for (int i = 0; i < 3; i++) {
                    createFloatingEmptyPanel();
                }
            }
        });

        menuBar.add(testMenu);
        setJMenuBar(menuBar);
    }

    private void createFloatingEmptyPanel() {
        // Create a simple DockablePanel
        DockablePanel panel = new DockablePanel();
        panel.setBackground(new Color(200, 220, 240)); // Light blue
        panel.setLayout(new BorderLayout());

        JLabel label = new JLabel(
            "<html><center>" +
            "<h3>Floating Empty Panel</h3>" +
            "<p>Hover + F9 to test docking</p>" +
            "</center></html>",
            SwingConstants.CENTER
        );
        panel.add(label, BorderLayout.CENTER);

        // Create floating window
        DockingWindow window = DockingWindow.createWithPanel(panel);
        window.setTitle("Empty Panel Test");
        window.setSize(300, 200);
        window.setLocation(getX() + 50 + (int)(Math.random() * 200),
                          getY() + 50 + (int)(Math.random() * 200));
        window.setVisible(true);

        System.out.println("Created floating empty panel window");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new EmptyDockingTest();
        });
    }
}