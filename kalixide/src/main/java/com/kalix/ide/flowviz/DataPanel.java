package com.kalix.ide.flowviz;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Panel for managing and displaying time series data in the FlowViz window.
 *
 * <p>This panel provides a data selection interface that allows users to:</p>
 * <ul>
 *   <li>View all loaded time series data</li>
 *   <li>Toggle visibility of individual series</li>
 *   <li>Reorder series display priority</li>
 *   <li>Select series for additional operations</li>
 * </ul>
 *
 * <p>The panel is designed as a data management interface, separate from
 * any plot legend functionality that may be added later.</p>
 */
public class DataPanel extends JPanel {
    private JPanel seriesListPanel;
    private JScrollPane scrollPane;
    private final List<SeriesDataItem> dataItems;

    // Listener interface for visibility changes
    public interface VisibilityChangeListener {
        void onVisibilityChanged();
    }

    private VisibilityChangeListener visibilityChangeListener;
    private SeriesDataItem selectedItem = null;

    public DataPanel() {
        dataItems = new ArrayList<>();

        setLayout(new BorderLayout());
        setBorder(new TitledBorder("Data"));
        setBackground(Color.WHITE);

        setupComponents();
        setupKeyboardHandling();
        showEmptyState();
    }

    private void setupComponents() {
        seriesListPanel = new JPanel();
        seriesListPanel.setLayout(new BoxLayout(seriesListPanel, BoxLayout.Y_AXIS));
        seriesListPanel.setBackground(Color.WHITE);

        scrollPane = new JScrollPane(seriesListPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);

        add(scrollPane, BorderLayout.CENTER);
    }

    private void showEmptyState() {
        seriesListPanel.removeAll();

        JPanel emptyPanel = new JPanel(new BorderLayout());
        emptyPanel.setBackground(Color.WHITE);
        emptyPanel.setBorder(BorderFactory.createEmptyBorder(20, 10, 20, 10));

        JLabel emptyLabel = new JLabel("<html><center>No data series<br>loaded yet</center></html>");
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyLabel.setForeground(Color.GRAY);
        emptyLabel.setFont(new Font("Arial", Font.PLAIN, 12));

        emptyPanel.add(emptyLabel, BorderLayout.CENTER);
        seriesListPanel.add(emptyPanel);

        revalidate();
        repaint();
    }

    public void addSeries(String seriesName, Color seriesColor, int pointCount) {
        // Remove empty state if this is the first series
        if (dataItems.isEmpty()) {
            seriesListPanel.removeAll();
        }

        SeriesDataItem item = new SeriesDataItem(seriesName, seriesColor, pointCount,
            () -> {
                if (visibilityChangeListener != null) {
                    visibilityChangeListener.onVisibilityChanged();
                }
            });
        dataItems.add(item);
        seriesListPanel.add(item);

        // Add some spacing between items
        seriesListPanel.add(Box.createVerticalStrut(2));

        revalidate();
        repaint();
    }

    public void removeSeries(String seriesName) {
        dataItems.removeIf(item -> item.getSeriesName().equals(seriesName));
        updateDataDisplay();
    }

    public void clearSeries() {
        dataItems.clear();
        showEmptyState();
    }

    private void updateDataDisplay() {
        seriesListPanel.removeAll();

        if (dataItems.isEmpty()) {
            showEmptyState();
        } else {
            for (SeriesDataItem item : dataItems) {
                seriesListPanel.add(item);
                seriesListPanel.add(Box.createVerticalStrut(2));
            }
        }

        revalidate();
        repaint();
    }


    public List<String> getVisibleSeries() {
        return dataItems.stream()
            .filter(SeriesDataItem::isSeriesVisible)
            .map(SeriesDataItem::getSeriesName)
            .toList();
    }

    public List<String> getAllSeries() {
        return dataItems.stream()
            .map(SeriesDataItem::getSeriesName)
            .toList();
    }

    public void setVisibilityChangeListener(VisibilityChangeListener listener) {
        this.visibilityChangeListener = listener;
    }

    private void setupKeyboardHandling() {
        setFocusable(true);

        // Add key bindings for moving items up/down (works on both Mac and PC)
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("meta UP"), "moveUp");    // Cmd+Up on Mac
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("meta DOWN"), "moveDown");  // Cmd+Down on Mac
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl UP"), "moveUp");    // Ctrl+Up on PC
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl DOWN"), "moveDown");  // Ctrl+Down on PC

        getActionMap().put("moveUp", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                moveSelectedItemUp();
            }
        });

        getActionMap().put("moveDown", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                moveSelectedItemDown();
            }
        });
    }

    private void setSelectedItem(SeriesDataItem item) {
        if (selectedItem != null) {
            selectedItem.setSelected(false);
        }
        selectedItem = item;
        if (selectedItem != null) {
            selectedItem.setSelected(true);
            requestFocusInWindow(); // Ensure panel can receive keyboard events
        }
    }

    public void moveSelectedItemUp() {
        if (selectedItem == null || dataItems.size() <= 1) return;

        int currentIndex = dataItems.indexOf(selectedItem);
        if (currentIndex <= 0) return; // Already at top or not found

        // Swap with previous item
        SeriesDataItem item = dataItems.remove(currentIndex);
        dataItems.add(currentIndex - 1, item);

        updateDataDisplay();
        notifyVisibilityChange();
    }

    public void moveSelectedItemDown() {
        if (selectedItem == null || dataItems.size() <= 1) return;

        int currentIndex = dataItems.indexOf(selectedItem);
        if (currentIndex < 0 || currentIndex >= dataItems.size() - 1) return; // Already at bottom or not found

        // Swap with next item
        SeriesDataItem item = dataItems.remove(currentIndex);
        dataItems.add(currentIndex + 1, item);

        updateDataDisplay();
        notifyVisibilityChange();
    }

    private void notifyVisibilityChange() {
        if (visibilityChangeListener != null) {
            visibilityChangeListener.onVisibilityChanged();
        }
    }

    // Inner class for individual data items
    private class SeriesDataItem extends JPanel {
        private final String seriesName;
        private final Color seriesColor;
        private final int pointCount;
        private final Runnable visibilityChangeCallback;
        private JCheckBox visibilityCheckbox;
        private boolean seriesVisible = true;
        private boolean selected = false;

        public SeriesDataItem(String seriesName, Color seriesColor, int pointCount, Runnable visibilityChangeCallback) {
            this.seriesName = seriesName;
            this.seriesColor = seriesColor;
            this.pointCount = pointCount;
            this.visibilityChangeCallback = visibilityChangeCallback;

            setupUI();
        }

        private void setupUI() {
            setLayout(new BorderLayout());
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)
            ));

            // Left side: checkbox and color sample
            JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            leftPanel.setBackground(Color.WHITE);

            visibilityCheckbox = new JCheckBox();
            visibilityCheckbox.setSelected(true);
            visibilityCheckbox.setBackground(Color.WHITE);
            visibilityCheckbox.addActionListener(e -> {
                seriesVisible = visibilityCheckbox.isSelected();
                updateAppearance();
                if (visibilityChangeCallback != null) {
                    visibilityChangeCallback.run();
                }
            });

            ColorSamplePanel colorSample = new ColorSamplePanel(seriesColor);
            colorSample.setPreferredSize(new Dimension(20, 3));

            leftPanel.add(visibilityCheckbox);
            leftPanel.add(Box.createHorizontalStrut(5));
            leftPanel.add(colorSample);

            // Right side: series info
            JPanel rightPanel = new JPanel(new BorderLayout());
            rightPanel.setBackground(Color.WHITE);

            JLabel nameLabel = new JLabel(seriesName);
            nameLabel.setFont(new Font("Arial", Font.BOLD, 11));
            nameLabel.setForeground(Color.BLACK);  // Explicitly set color

            JLabel infoLabel = new JLabel(String.format("%,d points", pointCount));
            infoLabel.setFont(new Font("Arial", Font.PLAIN, 9));
            infoLabel.setForeground(Color.GRAY);

            rightPanel.add(nameLabel, BorderLayout.NORTH);
            rightPanel.add(infoLabel, BorderLayout.SOUTH);

            add(leftPanel, BorderLayout.WEST);
            add(rightPanel, BorderLayout.CENTER);

            // Set size constraints after components are added
            Dimension prefSize = getPreferredSize();
            setMinimumSize(prefSize);
            setPreferredSize(prefSize);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, prefSize.height));

            // Add mouse listener for selection
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    setSelectedItem(SeriesDataItem.this);
                }
            });
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
            updateAppearance();
        }

        public boolean isSelected() {
            return selected;
        }

        private void updateAppearance() {
            float alpha = seriesVisible ? 1.0f : 0.5f;

            // Update border to show selection state
            if (selected) {
                setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color.BLUE, 2),
                    BorderFactory.createEmptyBorder(3, 6, 3, 6)
                ));
                setBackground(new Color(230, 240, 255)); // Light blue background
            } else {
                setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1),
                    BorderFactory.createEmptyBorder(5, 8, 5, 8)
                ));
                setBackground(Color.WHITE);
            }

            Component[] components = getAllComponents(this);
            for (Component comp : components) {
                if (comp instanceof JLabel label) {
                    Color currentColor = label.getForeground();
                    if (currentColor != null) {
                        // Create color with alpha, but ensure we have proper base colors
                        int red = currentColor.getRed();
                        int green = currentColor.getGreen();
                        int blue = currentColor.getBlue();
                        int alphaInt = (int)(255 * alpha);

                        Color newColor = new Color(red, green, blue, alphaInt);
                        label.setForeground(newColor);
                    } else {
                        // Fallback to black if foreground is null
                        label.setForeground(seriesVisible ? Color.BLACK : Color.GRAY);
                    }
                } else if (comp instanceof JPanel) {
                    comp.setBackground(selected ? new Color(230, 240, 255) : Color.WHITE);
                }
            }
            repaint();
        }

        private Component[] getAllComponents(Container container) {
            List<Component> components = new ArrayList<>();
            for (Component comp : container.getComponents()) {
                components.add(comp);
                if (comp instanceof Container) {
                    Collections.addAll(components, getAllComponents((Container) comp));
                }
            }
            return components.toArray(new Component[0]);
        }

        public String getSeriesName() {
            return seriesName;
        }

        public Color getSeriesColor() {
            return seriesColor;
        }

        public boolean isSeriesVisible() {
            return seriesVisible;
        }

        public void setSeriesVisible(boolean visible) {
            seriesVisible = visible;
            visibilityCheckbox.setSelected(visible);
            updateAppearance();
        }
    }

    // Inner class for color sample display
    private static class ColorSamplePanel extends JPanel {
        private final Color color;

        public ColorSamplePanel(Color color) {
            this.color = color;
            setBackground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Draw line sample
            g2d.setColor(color);
            g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            int y = getHeight() / 2;
            g2d.drawLine(2, y, getWidth() - 2, y);

            g2d.dispose();
        }
    }
}