package com.kalix.ide.managers;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Manages a filter text field for JTree visual filtering.
 *
 * The filter is purely visual - it does NOT affect tree selection state,
 * plotted series, or the selectedSeries set. When filter text changes,
 * triggers a callback so the caller can rebuild the tree with filtering applied.
 */
public class TreeFilterManager {

    private static final int DEBOUNCE_DELAY_MS = 150;
    private static final int CLEAR_ICON_SIZE = 12;

    private final JTextField filterField;
    private final JButton clearButton;
    private final JPanel filterPanel;
    private final Runnable onFilterChanged;
    private Timer debounceTimer;

    public TreeFilterManager(Runnable onFilterChanged) {
        this.onFilterChanged = onFilterChanged;
        this.filterField = createFilterField();
        this.clearButton = createClearButton();
        this.filterPanel = createFilterPanel();
    }

    public JPanel getFilterPanel() {
        return filterPanel;
    }

    public String getFilterText() {
        return filterField.getText().trim();
    }

    public boolean isFiltering() {
        return !getFilterText().isEmpty();
    }

    public void clearFilter() {
        filterField.setText("");
    }

    private JTextField createFilterField() {
        JTextField field = new JTextField();
        field.putClientProperty("JTextField.placeholderText", "Filter...");

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { scheduleFilterUpdate(); }
            @Override
            public void removeUpdate(DocumentEvent e) { scheduleFilterUpdate(); }
            @Override
            public void changedUpdate(DocumentEvent e) { scheduleFilterUpdate(); }
        });

        field.registerKeyboardAction(
            e -> clearFilter(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_FOCUSED
        );

        return field;
    }

    private JButton createClearButton() {
        FontIcon icon = FontIcon.of(FontAwesomeSolid.TIMES_CIRCLE, CLEAR_ICON_SIZE);
        icon.setIconColor(UIManager.getColor("Label.disabledForeground"));

        JButton button = new JButton(icon);
        button.setToolTipText("Clear filter");
        button.setFocusable(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setMargin(new Insets(0, 2, 0, 2));
        button.setVisible(false);
        button.addActionListener(e -> clearFilter());
        return button;
    }

    private JPanel createFilterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(filterField, BorderLayout.CENTER);
        panel.add(clearButton, BorderLayout.EAST);
        return panel;
    }

    private void scheduleFilterUpdate() {
        if (debounceTimer != null && debounceTimer.isRunning()) {
            debounceTimer.stop();
        }
        debounceTimer = new Timer(DEBOUNCE_DELAY_MS, e -> {
            clearButton.setVisible(isFiltering());
            onFilterChanged.run();
        });
        debounceTimer.setRepeats(false);
        debounceTimer.start();
    }
}
