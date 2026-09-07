package com.kalix.ide.dataview;

import com.kalix.ide.io.CsvDates;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;

/**
 * The data table's one Find dialog, deliberately shaped like the model
 * editor's ({@code TextSearchManager}): non-modal, a single search field,
 * stacked tickboxes, an inline status line (never a modal "not found" popup),
 * and a Find Next / Find Previous / Close button row with Enter finding next
 * and Escape closing.
 *
 * <p>The tickboxes split into scopes — Dates (the first column, matched as
 * text <em>and</em> by parsed date), Values (data cells), Columns (header
 * names, which navigate horizontally) — and options: Match case, Whole cell
 * (the cell-grained analogue of the editor's whole-word), Wrap around.
 */
final class DataFindDialog {

    private final JDialog dialog;
    private final JTextField searchField = new JTextField(20);
    private final JCheckBox datesCheckBox = new JCheckBox("Dates", true);
    private final JCheckBox valuesCheckBox = new JCheckBox("Values", true);
    private final JCheckBox columnsCheckBox = new JCheckBox("Columns", true);
    private final JCheckBox matchCaseCheckBox = new JCheckBox("Match case");
    private final JCheckBox wholeCellCheckBox = new JCheckBox("Whole cell");
    private final JCheckBox wrapAroundCheckBox = new JCheckBox("Wrap around", true);
    private final JLabel statusLabel = new JLabel(" ");

    DataFindDialog(DataViewPanel owner) {
        Window window = SwingUtilities.getWindowAncestor(owner);
        dialog = new JDialog(window instanceof Frame frame ? frame : null, "Find", false);
        dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        panel.add(new JLabel("Find:"), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(searchField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        panel.add(statusLabel, gbc);

        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JCheckBox[] boxes = {datesCheckBox, valuesCheckBox, columnsCheckBox,
            matchCaseCheckBox, wholeCellCheckBox, wrapAroundCheckBox};
        for (JCheckBox box : boxes) {
            gbc.gridy++;
            panel.add(box, gbc);
        }

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton findButton = new JButton("Find Next");
        findButton.addActionListener(e -> owner.runFind(true));
        buttonPanel.add(findButton);

        JButton findPrevButton = new JButton("Find Previous");
        findPrevButton.addActionListener(e -> owner.runFind(false));
        buttonPanel.add(findPrevButton);

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dialog.setVisible(false));
        buttonPanel.add(closeButton);

        gbc.gridy++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(buttonPanel, gbc);

        searchField.addActionListener(e -> owner.runFind(true));

        dialog.getRootPane().registerKeyboardAction(
            e -> dialog.setVisible(false),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.add(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
    }

    /** Shows the dialog, pre-filling from {@code prefill} (the selected cell) when present. */
    void showOver(String prefill) {
        if (prefill != null && !prefill.isBlank()) {
            searchField.setText(prefill);
        }
        searchField.selectAll();
        dialog.setVisible(true);
        dialog.toFront();
        searchField.requestFocusInWindow();
    }

    boolean isShowing() {
        return dialog.isVisible();
    }

    String queryText() {
        return searchField.getText();
    }

    boolean columnsScope() {
        return columnsCheckBox.isSelected();
    }

    boolean wrapEnabled() {
        return wrapAroundCheckBox.isSelected();
    }

    /** The current search spec; the date interpretation is derived from the query itself. */
    DataViewSession.FindSpec spec() {
        String query = searchField.getText().trim();
        Long dateMillis = null;
        boolean dateOnly = false;
        if (datesCheckBox.isSelected() && !query.isEmpty()) {
            CsvDates.Spec dateSpec = CsvDates.detect(query);
            if (dateSpec != null) {
                long parsed = CsvDates.parseMillis(query, dateSpec);
                if (parsed != CsvDates.INVALID_TS) {
                    dateMillis = parsed;
                    dateOnly = dateSpec.dateOnly();
                }
            }
        }
        return new DataViewSession.FindSpec(query, matchCaseCheckBox.isSelected(),
            wholeCellCheckBox.isSelected(), datesCheckBox.isSelected(), valuesCheckBox.isSelected(),
            dateMillis, dateOnly);
    }

    /**
     * Inline feedback, editor-style: problems are coloured, ordinary counts are
     * not, so a miss reads as different in kind from "3 of 17".
     */
    void setStatus(String message, boolean problem) {
        statusLabel.setText(message);
        statusLabel.setForeground(problem ? problemColor() : UIManager.getColor("Label.foreground"));
    }

    /** A red that reads on both light and dark themes, preferring the theme's own. */
    private static Color problemColor() {
        Color themed = UIManager.getColor("Actions.Red");
        return themed != null ? themed : new Color(0xC7, 0x52, 0x4A);
    }

    void disposeDialog() {
        dialog.dispose();
    }
}
