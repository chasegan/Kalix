package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Simple working test for the docking system without the infinite loop issue.
 */
public class SimpleDockingTest extends JFrame {

    private boolean isHovered = false;
    private boolean f9Pressed = false;
    private JPanel testPanel;
    private JLabel statusLabel;

    public SimpleDockingTest() {
        System.out.println("Starting SimpleDockingTest...");

        setTitle("Working Docking Test - Hover + F9");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(600, 400);
        setLocationRelativeTo(null);

        setupUI();
        setupKeyHandling();

        System.out.println("SimpleDockingTest ready!");
        setVisible(true);
    }

    private void setupUI() {
        setLayout(new BorderLayout());

        // Create test panel that mimics DockablePanel behavior
        testPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);

                // Draw highlight if both hovered and F9 pressed
                if (isHovered && f9Pressed) {
                    Graphics2D g2d = (Graphics2D) g.create();
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    // Draw translucent blue highlight
                    g2d.setColor(new Color(0, 123, 255, 60));
                    g2d.setStroke(new BasicStroke(3.0f));
                    g2d.drawRect(1, 1, getWidth() - 3, getHeight() - 3);

                    // Draw grip in top-left corner
                    int gripX = 5;
                    int gripY = 5;
                    int gripW = 30;
                    int gripH = 20;

                    // Grip background
                    g2d.setColor(new Color(0, 90, 190, 180));
                    g2d.fillRoundRect(gripX, gripY, gripW, gripH, 6, 6);

                    // Draw dots with inset effect
                    g2d.setColor(new Color(0, 123, 255, 120));
                    for (int row = 0; row < 2; row++) {
                        for (int col = 0; col < 3; col++) {
                            int dotX = gripX + 7 + col * 6;
                            int dotY = gripY + 6 + row * 6;

                            // Shadow
                            g2d.setColor(new Color(0, 0, 0, 100));
                            g2d.fillOval(dotX - 1, dotY - 1, 3, 3);

                            // Highlight
                            g2d.setColor(new Color(255, 255, 255, 80));
                            g2d.fillOval(dotX + 1, dotY + 1, 3, 3);

                            // Main dot
                            g2d.setColor(new Color(0, 123, 255, 120));
                            g2d.fillOval(dotX, dotY, 3, 3);
                        }
                    }

                    g2d.dispose();
                }
            }
        };

        testPanel.setBackground(Color.LIGHT_GRAY);
        testPanel.setFocusable(true);

        // Add content to panel
        testPanel.setLayout(new BorderLayout());
        JLabel instructions = new JLabel("<html><center>Hover over this panel<br>and press F9<br>to see docking highlight</center></html>", SwingConstants.CENTER);
        instructions.setFont(instructions.getFont().deriveFont(16f));
        testPanel.add(instructions, BorderLayout.CENTER);

        // Mouse listeners for hover detection
        testPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                isHovered = true;
                updateStatus();
                testPanel.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                isHovered = false;
                updateStatus();
                testPanel.repaint();
            }
        });

        add(testPanel, BorderLayout.CENTER);

        // Status label
        statusLabel = new JLabel("Ready - Hover over panel and press F9");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(statusLabel, BorderLayout.SOUTH);
    }

    private void setupKeyHandling() {
        // Use a more direct approach - add key listener to the frame and panel
        KeyListener keyListener = new KeyListener() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_F9) {
                    f9Pressed = true;
                    updateStatus();
                    testPanel.repaint();
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_F9) {
                    f9Pressed = false;
                    updateStatus();
                    testPanel.repaint();
                }
            }

            @Override
            public void keyTyped(KeyEvent e) {
                // Not used
            }
        };

        // Add to both frame and panel for better key capture
        addKeyListener(keyListener);
        testPanel.addKeyListener(keyListener);

        // Request focus for key events
        addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowGainedFocus(java.awt.event.WindowEvent e) {
                testPanel.requestFocusInWindow();
            }
        });
    }

    private void updateStatus() {
        String status = String.format("Hovered: %s, F9: %s", isHovered, f9Pressed);
        if (isHovered && f9Pressed) {
            status += " - DOCKING MODE ACTIVE!";
        }
        statusLabel.setText(status);
        System.out.println(status);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new SimpleDockingTest();
        });
    }
}