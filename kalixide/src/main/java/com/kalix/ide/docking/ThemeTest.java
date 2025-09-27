package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

/**
 * Test application to demonstrate the dynamic theming system.
 */
public class ThemeTest extends JFrame {

    private DockablePanel testPanel;

    public ThemeTest() {
        setTitle("Docking Theme Test");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);

        setupUI();
        setVisible(true);
    }

    private void setupUI() {
        setLayout(new BorderLayout());

        // Create test panel
        testPanel = new DockablePanel();
        testPanel.setBackground(Color.LIGHT_GRAY);
        testPanel.setLayout(new BorderLayout());

        JLabel instructions = new JLabel(
            "<html><center>" +
            "<h2>Dynamic Theme Test</h2>" +
            "<p>Hover here and press F9 to see theme colors</p>" +
            "<p>Use buttons above to change themes</p>" +
            "</center></html>",
            SwingConstants.CENTER
        );
        instructions.setFont(instructions.getFont().deriveFont(16f));
        testPanel.add(instructions, BorderLayout.CENTER);

        // Put in docking area
        DockingArea mainArea = new DockingArea("Main Area");
        mainArea.addDockablePanel(testPanel);
        add(mainArea, BorderLayout.CENTER);

        // Create theme selection toolbar
        add(createThemeToolbar(), BorderLayout.NORTH);

        // Status
        JLabel status = new JLabel("Current theme: Default Blue on Light");
        status.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        add(status, BorderLayout.SOUTH);
    }

    private JToolBar createThemeToolbar() {
        JToolBar toolbar = new JToolBar("Themes");
        toolbar.setFloatable(false);

        // Light themes
        toolbar.add(createThemeButton("Blue Light", Color.BLUE, Color.WHITE));
        toolbar.add(createThemeButton("Green Light", Color.GREEN, Color.WHITE));
        toolbar.add(createThemeButton("Red Light", Color.RED, Color.WHITE));
        toolbar.add(createThemeButton("Purple Light", new Color(147, 51, 234), Color.WHITE));

        toolbar.addSeparator();

        // Dark themes
        toolbar.add(createThemeButton("Blue Dark", Color.BLUE, Color.DARK_GRAY));
        toolbar.add(createThemeButton("Green Dark", Color.GREEN, Color.DARK_GRAY));
        toolbar.add(createThemeButton("Red Dark", Color.RED, Color.DARK_GRAY));
        toolbar.add(createThemeButton("Purple Dark", new Color(147, 51, 234), Color.DARK_GRAY));

        toolbar.addSeparator();

        // Custom themes
        toolbar.add(createThemeButton("Orange Dark", new Color(255, 165, 0), new Color(30, 30, 30)));
        toolbar.add(createThemeButton("Cyan Light", Color.CYAN, new Color(250, 250, 250)));

        return toolbar;
    }

    private JButton createThemeButton(String name, Color accent, Color background) {
        JButton button = new JButton(name);
        button.addActionListener(e -> {
            // Set standard UIManager properties
            UIManager.put("Panel.background", background);
            UIManager.put("Component.focusColor", accent);
            UIManager.put("control", background); // Fallback property

            // Update docking system from UIManager
            DockingConstants.Colors.updateFromUIManager();

            // Update actual panel backgrounds to match
            updatePanelBackgrounds(background);

            // Update status
            Component statusLabel = ((BorderLayout) getContentPane().getLayout())
                .getLayoutComponent(BorderLayout.SOUTH);
            if (statusLabel instanceof JLabel) {
                String mode = isLight(background) ? "Light" : "Dark";
                ((JLabel) statusLabel).setText("Current theme: " + name + " (" + mode + " mode)");
            }

            // Force repaint
            repaint();
        });
        return button;
    }

    private void updatePanelBackgrounds(Color background) {
        // Update test panel background
        if (testPanel != null) {
            testPanel.setBackground(background);
        }

        // Update docking area background
        Component[] components = getContentPane().getComponents();
        for (Component comp : components) {
            if (comp instanceof DockingArea) {
                comp.setBackground(background);
                // Update text color for contrast
                updateTextColor((DockingArea) comp, background);
            }
        }
    }

    private void updateTextColor(DockingArea area, Color background) {
        boolean isLight = isLight(background);
        Color textColor = isLight ? Color.BLACK : Color.WHITE;

        // Update any labels in the docking area
        updateComponentTextColors(area, textColor);
    }

    private void updateComponentTextColors(Container container, Color textColor) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JLabel) {
                comp.setForeground(textColor);
            } else if (comp instanceof Container) {
                updateComponentTextColors((Container) comp, textColor);
            }
        }
    }

    private boolean isLight(Color color) {
        int brightness = (int)(0.299 * color.getRed() +
                             0.587 * color.getGreen() +
                             0.114 * color.getBlue());
        return brightness > 128;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new ThemeTest();
        });
    }
}