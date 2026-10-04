package com.kalix.ide.managers;

import com.formdev.flatlaf.FlatClientProperties;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;

/**
 * Manages a filter text field for JTree visual filtering.
 *
 * The filter is purely visual - it does NOT affect tree selection state,
 * plotted series, or the selectedSeries set. When filter text changes,
 * triggers a callback so the caller can rebuild the tree with filtering applied.
 * Text that doesn't parse (see {@link SeriesFilter}) outlines the field in red,
 * explains why in its tooltip, and leaves the last valid filter applied.
 */
public class TreeFilterManager {

    private static final int DEBOUNCE_DELAY_MS = 150;
    private static final int CLEAR_ICON_SIZE = 12;
    private static final String PLACEHOLDER = "Filter, e.g. inflow_* !dummy";
    private static final String SYNTAX_TOOLTIP =
        "Show series matching every term. * and ? are wildcards (whole parts), ! excludes, "
            + "/.../ is a regex, \"...\" keeps spaces.";

    private final JTextField filterField;
    private final JButton clearButton;
    private final JPanel filterPanel;
    private final Runnable onFilterChanged;
    private Timer debounceTimer;
    private SeriesFilter applied = SeriesFilter.NONE;
    /** The text {@link #applied} came from, so re-applying the same text rebuilds nothing. */
    private String appliedText = "";

    public TreeFilterManager(Runnable onFilterChanged) {
        this.onFilterChanged = onFilterChanged;
        this.filterField = createFilterField();
        this.clearButton = createClearButton();
        this.filterPanel = createFilterPanel();
    }

    public JPanel getFilterPanel() {
        return filterPanel;
    }

    /** The last filter that parsed; invalid text leaves it in place. */
    public SeriesFilter getFilter() {
        return applied;
    }

    /** Clears the box and applies the empty filter at once, not after the debounce. */
    public void clearFilter() {
        if (debounceTimer != null) {
            debounceTimer.stop();
        }
        filterField.setText("");
        apply("");
    }

    private JTextField createFilterField() {
        JTextField field = new JTextField();
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, PLACEHOLDER);
        field.setToolTipText(SYNTAX_TOOLTIP);

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
        debounceTimer = new Timer(DEBOUNCE_DELAY_MS, e -> apply(filterField.getText()));
        debounceTimer.setRepeats(false);
        debounceTimer.start();
    }

    /**
     * Applies {@code text} as the filter: a parse error outlines the field and
     * puts the reason in its tooltip, leaving the last valid filter in place;
     * valid text clears both and tells the caller to rebuild. Package-private
     * so tests can drive it without the debounce timer.
     */
    void apply(String text) {
        clearButton.setVisible(!text.isBlank());
        SeriesFilter parsed;
        try {
            parsed = SeriesFilter.parse(text);
        } catch (SeriesFilter.SyntaxException ex) {
            filterField.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
            filterField.setToolTipText(ex.getMessage());
            return;
        }
        filterField.putClientProperty(FlatClientProperties.OUTLINE, null);
        filterField.setToolTipText(SYNTAX_TOOLTIP);
        if (text.strip().equals(appliedText)) {
            return;
        }
        applied = parsed;
        appliedText = text.strip();
        onFilterChanged.run();
    }

    /** The field's tooltip: the syntax summary, or the reason the text does not parse. */
    String getTooltip() {
        return filterField.getToolTipText();
    }

    /** Whether the field is outlined as an error. */
    boolean isShowingError() {
        return FlatClientProperties.OUTLINE_ERROR.equals(filterField.getClientProperty(FlatClientProperties.OUTLINE));
    }
}
