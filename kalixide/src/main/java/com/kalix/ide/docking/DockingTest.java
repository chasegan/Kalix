package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Simple test to verify the docking system basics work.
 */
public class DockingTest extends JFrame {

    public DockingTest() {
        System.out.println("DockingTest starting...");

        setTitle("Simple Docking Test");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);

        // Create a simple dockable panel with just basic content
        DockablePanel testPanel = new DockablePanel();
        testPanel.setBackground(Color.LIGHT_GRAY);
        testPanel.add(new JLabel("Hover here and press F9", SwingConstants.CENTER));

        // Add it directly to the frame
        add(testPanel, BorderLayout.CENTER);

        // Add status at bottom
        JLabel status = new JLabel("Test: Hover over gray area and press F9 to see docking highlight");
        status.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(status, BorderLayout.SOUTH);

        // Global key listener for debugging
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED) {
                System.out.println("Global key pressed: " + KeyEvent.getKeyText(e.getKeyCode()) + " (" + e.getKeyCode() + ")");
                if (e.getKeyCode() == KeyEvent.VK_F9) {
                    System.out.println("F9 detected globally!");
                    testPanel.dispatchEvent(e);
                    return true;
                }
            } else if (e.getID() == KeyEvent.KEY_RELEASED) {
                if (e.getKeyCode() == KeyEvent.VK_F9) {
                    System.out.println("F9 released globally!");
                    testPanel.dispatchEvent(e);
                    return true;
                }
            }
            return false;
        });

        System.out.println("DockingTest setup complete");
        setVisible(true);
    }

    public static void main(String[] args) {
        System.out.println("Starting main method...");
        SwingUtilities.invokeLater(() -> {
            System.out.println("Creating DockingTest...");
            new DockingTest();
        });
    }
}