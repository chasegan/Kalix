package com.kalix.ide.windows;

import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.PlotPalette;
import com.kalix.ide.flowviz.style.PlotPaletteManager;
import com.kalix.ide.flowviz.style.StrokeStyle;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Modeless editor for plot palettes — the user-facing surface of the custom
 * palette feature.
 *
 * <h2>Layout</h2>
 * A palette selector ({@code JComboBox} + Duplicate / Rename / Delete) sits above
 * a fixed grid of {@link PlotPalette#SLOT_COUNT} rows. Each row edits one slot: a
 * colour swatch and editable {@code #RRGGBBAA} hex field on the left, a combined
 * stroke dropdown on the right.
 *
 * <h2>Data flow</h2>
 * This window is the only thing that mutates palettes, so it drives
 * {@link PlotPaletteManager} imperatively and updates its own widgets directly —
 * it does not listen back to the manager. Selecting a palette makes it the global
 * active palette; editing a slot commits a new {@link PlotPalette} via
 * {@code updatePalette}. Either way the manager fires its change event, which the
 * open {@code PlotPanel}s observe and repaint — that is the live propagation.
 *
 * <p>One shared instance; {@link #showWindow()} creates it on first use and
 * re-shows it thereafter.</p>
 */
public final class PlotPaletteWindow extends JFrame {

    private static PlotPaletteWindow instance;

    private final PlotPaletteManager manager;

    /** The palette currently shown and edited; always equal to the active palette. */
    private PlotPalette currentPalette;

    /** Suppresses widget listeners while the UI is being populated programmatically. */
    private boolean refreshing = false;

    /** Fixed combo width — keeps the selector's footprint stable for any palette name. */
    private static final int COMBO_WIDTH = 240;

    private final JComboBox<String> paletteCombo = new JComboBox<>();
    private final JButton duplicateButton = new JButton("Duplicate");
    private final JButton renameButton = new JButton("Rename");
    private final JButton deleteButton = new JButton("Delete");
    private final SlotRow[] rows = new SlotRow[PlotPalette.SLOT_COUNT];

    /** Opens the palette window, or brings the existing one to the front. */
    public static void showWindow() {
        if (instance == null) {
            instance = new PlotPaletteWindow(PlotPaletteManager.getInstance());
        }
        instance.setVisible(true);
        instance.setExtendedState(NORMAL);
        instance.toFront();
        instance.requestFocus();
    }

    private PlotPaletteWindow(PlotPaletteManager manager) {
        super("Plot Palettes");
        this.manager = manager;

        setContentPane(buildContent());
        loadFromManager();
        pack();
        setResizable(false);
        setLocationRelativeTo(null);
    }

    // ==== UI construction ====

    private JPanel buildContent() {
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        content.add(buildSelectorPanel(), BorderLayout.NORTH);
        content.add(buildEditorPanel(), BorderLayout.CENTER);
        return content;
    }

    /**
     * Builds the selector area: the palette dropdown on one line and the
     * management buttons on the next. Keeping them on separate lines, and pinning
     * the combo to a fixed width, means a long palette name can never widen this
     * area enough to wrap or clip — the window's layout stays stable.
     */
    private JPanel buildSelectorPanel() {
        paletteCombo.setPreferredSize(
            new Dimension(COMBO_WIDTH, paletteCombo.getPreferredSize().height));
        paletteCombo.addActionListener(e -> {
            if (refreshing) {
                return;
            }
            String name = (String) paletteCombo.getSelectedItem();
            if (name != null && !name.equals(manager.getActivePaletteName())) {
                manager.setActivePalette(name);
                loadEditor();
            }
        });

        duplicateButton.addActionListener(e -> duplicateActivePalette());
        renameButton.addActionListener(e -> renameActivePalette());
        deleteButton.addActionListener(e -> deleteActivePalette());

        JPanel comboRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        comboRow.add(new JLabel("Palette:"));
        comboRow.add(paletteCombo);
        comboRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        buttonRow.add(duplicateButton);
        buttonRow.add(renameButton);
        buttonRow.add(deleteButton);
        buttonRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(comboRow);
        panel.add(buttonRow);
        return panel;
    }

    private JPanel buildEditorPanel() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(BorderFactory.createEmptyBorder(8, 2, 0, 2));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.anchor = GridBagConstraints.WEST;

        // Column headers.
        gbc.gridy = 0;
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        grid.add(headerLabel("Colour"), gbc);
        gbc.gridx = 3;
        gbc.gridwidth = 1;
        grid.add(headerLabel("Stroke"), gbc);

        // One row per palette slot.
        for (int slot = 0; slot < PlotPalette.SLOT_COUNT; slot++) {
            SlotRow row = new SlotRow(slot);
            rows[slot] = row;

            gbc.gridy = slot + 1;
            gbc.gridx = 0;
            grid.add(new JLabel((slot + 1) + "."), gbc);
            gbc.gridx = 1;
            grid.add(row.swatch, gbc);
            gbc.gridx = 2;
            grid.add(row.hexField, gbc);
            gbc.gridx = 3;
            grid.add(row.strokeCombo, gbc);
        }

        return grid;
    }

    private static JLabel headerLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    // ==== Loading state from the manager ====

    private void loadFromManager() {
        rebuildCombo();
        loadEditor();
    }

    /** Repopulates the palette dropdown from the manager and selects the active palette. */
    private void rebuildCombo() {
        refreshing = true;
        try {
            paletteCombo.removeAllItems();
            for (PlotPalette palette : manager.getPalettes()) {
                paletteCombo.addItem(palette.name());
            }
            paletteCombo.setSelectedItem(manager.getActivePaletteName());
        } finally {
            refreshing = false;
        }
    }

    /** Loads the active palette into the ten slot rows and updates enablement. */
    private void loadEditor() {
        refreshing = true;
        try {
            currentPalette = manager.getActivePalette();
            boolean editable = !currentPalette.builtIn();
            for (int slot = 0; slot < PlotPalette.SLOT_COUNT; slot++) {
                rows[slot].load(currentPalette.entryAt(slot));
                rows[slot].setEditable(editable);
            }
            renameButton.setEnabled(editable);
            deleteButton.setEnabled(editable);
        } finally {
            refreshing = false;
        }
    }

    // ==== Palette management actions ====

    private void duplicateActivePalette() {
        String name = JOptionPane.showInputDialog(this,
            "Name for the new palette:", currentPalette.name() + " copy");
        if (name == null) {
            return;  // cancelled
        }
        try {
            manager.duplicate(currentPalette.name(), name);
            manager.setActivePalette(name.trim());
            rebuildCombo();
            loadEditor();
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
        }
    }

    private void renameActivePalette() {
        String name = JOptionPane.showInputDialog(this,
            "New name for this palette:", currentPalette.name());
        if (name == null) {
            return;
        }
        try {
            manager.rename(currentPalette.name(), name);
            rebuildCombo();
            loadEditor();
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
        }
    }

    private void deleteActivePalette() {
        int choice = JOptionPane.showConfirmDialog(this,
            "Delete the palette \"" + currentPalette.name() + "\"?",
            "Delete Palette", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            manager.delete(currentPalette.name());
            rebuildCombo();
            loadEditor();
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Palette", JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Commits an edited slot: replaces it in the current palette and pushes the
     * result through the manager, which fires the change event the plots observe.
     */
    private void commitSlot(int slot, LineStyle style) {
        currentPalette = currentPalette.withEntry(slot, style);
        manager.updatePalette(currentPalette);
    }

    // ==== Hex helpers ====

    /** Formats a colour as {@code #RRGGBBAA}. */
    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x%02x",
            c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }

    /**
     * Parses {@code #RRGGBB} or {@code #RRGGBBAA} (the leading {@code #} optional),
     * or {@code null} if the text is not a valid 6- or 8-digit hex colour. A 6-digit
     * value is treated as fully opaque.
     */
    private static Color parseHex(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (!s.matches("(?i)[0-9a-f]{6}([0-9a-f]{2})?")) {
            return null;
        }
        int r = Integer.parseInt(s.substring(0, 2), 16);
        int g = Integer.parseInt(s.substring(2, 4), 16);
        int b = Integer.parseInt(s.substring(4, 6), 16);
        int a = s.length() == 8 ? Integer.parseInt(s.substring(6, 8), 16) : 255;
        return new Color(r, g, b, a);
    }

    // ==== Slot row ====

    /**
     * The widgets and edit logic for one palette slot: swatch + hex field (colour)
     * and a combined-stroke dropdown.
     */
    private final class SlotRow {
        private final int slot;
        private final SwatchButton swatch = new SwatchButton();
        private final JTextField hexField = new JTextField(9);
        private final JComboBox<StrokeStyle> strokeCombo = new JComboBox<>(StrokeStyle.values());

        SlotRow(int slot) {
            this.slot = slot;

            swatch.setOnClick(this::chooseColourGraphically);

            hexField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            hexField.addActionListener(e -> commitHex());
            hexField.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    commitHex();
                }
            });

            strokeCombo.setRenderer(new StrokeCellRenderer());
            strokeCombo.addActionListener(e -> commitStroke());
        }

        /** Populates the widgets from {@code style} without firing edit listeners. */
        void load(LineStyle style) {
            swatch.setColour(style.color());
            hexField.setText(toHex(style.color()));
            strokeCombo.setSelectedItem(style.stroke());
        }

        void setEditable(boolean editable) {
            // setEnabled (not setEditable) so the field greys out like the swatch and
            // stroke combo, and cannot be focused — the standard read-only appearance.
            swatch.setEnabled(editable);
            hexField.setEnabled(editable);
            strokeCombo.setEnabled(editable);
        }

        /** Commits the hex field; invalid text is rejected and reverted. */
        private void commitHex() {
            if (refreshing) {
                return;
            }
            Color parsed = parseHex(hexField.getText());
            if (parsed == null) {
                Toolkit.getDefaultToolkit().beep();
                load(currentPalette.entryAt(slot));  // revert to last good value
                return;
            }
            hexField.setText(toHex(parsed));  // normalise (e.g. expand 6-digit form)
            swatch.setColour(parsed);
            commitSlot(slot, currentPalette.entryAt(slot).withColor(parsed));
        }

        private void commitStroke() {
            if (refreshing) {
                return;
            }
            StrokeStyle stroke = (StrokeStyle) strokeCombo.getSelectedItem();
            commitSlot(slot, currentPalette.entryAt(slot).withStroke(stroke));
        }

        /**
         * Opens a graphical chooser for the slot's colour. {@code JColorChooser}
         * has no alpha channel, so the slot's current opacity is preserved — the
         * hex field remains the way to change opacity.
         */
        private void chooseColourGraphically() {
            Color current = currentPalette.entryAt(slot).color();
            Color rgb = JColorChooser.showDialog(PlotPaletteWindow.this, "Choose Colour", current);
            if (rgb != null) {
                Color withAlpha = new Color(
                    rgb.getRed(), rgb.getGreen(), rgb.getBlue(), current.getAlpha());
                hexField.setText(toHex(withAlpha));
                swatch.setColour(withAlpha);
                commitSlot(slot, currentPalette.entryAt(slot).withColor(withAlpha));
            }
        }
    }

    // ==== Swatch component ====

    /**
     * A clickable colour swatch. Paints the colour over a light checkerboard so a
     * translucent colour reads as translucent.
     */
    private static final class SwatchButton extends JComponent {
        private static final int CHECKER = 5;

        private Color colour = Color.GRAY;
        private Runnable onClick;

        SwatchButton() {
            setPreferredSize(new Dimension(30, 20));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (isEnabled() && onClick != null) {
                        onClick.run();
                    }
                }
            });
        }

        void setColour(Color colour) {
            this.colour = colour;
            repaint();
        }

        void setOnClick(Runnable onClick) {
            this.onClick = onClick;
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setCursor(Cursor.getPredefinedCursor(
                enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            for (int y = 0; y < h; y += CHECKER) {
                for (int x = 0; x < w; x += CHECKER) {
                    boolean light = ((x / CHECKER) + (y / CHECKER)) % 2 == 0;
                    g.setColor(light ? Color.WHITE : new Color(0xCCCCCC));
                    g.fillRect(x, y, CHECKER, CHECKER);
                }
            }
            g.setColor(colour);  // alpha channel blends over the checkerboard
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(0x808080));
            g.drawRect(0, 0, w - 1, h - 1);
        }
    }

    // ==== Stroke dropdown rendering ====

    /** Renders a {@link StrokeStyle} item as a sample line plus its display name. */
    private static final class StrokeCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof StrokeStyle stroke) {
                setText(" " + stroke.getDisplayName());
                setIcon(new StrokeIcon(stroke));
            }
            return this;
        }
    }

    /** A small {@link Icon} drawing a horizontal sample of one {@link StrokeStyle}. */
    private static final class StrokeIcon implements Icon {
        private static final int WIDTH = 44;
        private static final int HEIGHT = 14;

        private final StrokeStyle stroke;

        StrokeIcon(StrokeStyle stroke) {
            this.stroke = stroke;
        }

        @Override
        public int getIconWidth() {
            return WIDTH;
        }

        @Override
        public int getIconHeight() {
            return HEIGHT;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(c.getForeground());
            g2.setStroke(stroke.toBasicStroke());
            int midY = y + HEIGHT / 2;
            g2.drawLine(x + 3, midY, x + WIDTH - 3, midY);
            g2.dispose();
        }
    }
}
