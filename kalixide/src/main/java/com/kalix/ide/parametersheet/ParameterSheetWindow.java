package com.kalix.ide.parametersheet;

import com.kalix.ide.constants.UIConstants;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.parsing.INIModelParser.NodeSection;
import com.kalix.ide.editor.EnhancedTextEditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.text.Document;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.table.TableRowSorter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Non-modal dialog showing all node parameters in a spreadsheet-like table.
 * Rows are nodes, columns are property keys. Supports filtering by node type
 * and name pattern. Editable cells are tracked and applied atomically on OK.
 */
public class ParameterSheetWindow extends JDialog {

    private static final Logger logger = LoggerFactory.getLogger(ParameterSheetWindow.class);

    private static final int MAX_COLUMN_WIDTH = 300;
    private static final int MIN_COLUMN_WIDTH = 60;
    private static final int COLUMN_PADDING = 16;

    private final Supplier<INIModelParser.ParsedModel> modelSupplier;
    private final EnhancedTextEditor textEditor;

    private final ParameterSheetTableModel tableModel;
    private JTable table;
    private TableRowSorter<ParameterSheetTableModel> sorter;
    private JComboBox<String> typeFilterCombo;
    private JTextField nameFilterField;
    private JButton okButton;

    private DocumentListener externalEditListener;
    private boolean externalEditDetected = false;
    private Map<String, NodeSection> allNodes;

    /**
     * Creates and shows the Parameter Sheet window.
     *
     * @param parent        parent frame
     * @param modelSupplier supplier for the parsed model
     * @param textEditor    the text editor for applying changes and listening for external edits
     */
    public ParameterSheetWindow(JFrame parent,
                                 Supplier<INIModelParser.ParsedModel> modelSupplier,
                                 EnhancedTextEditor textEditor) {
        super(parent, "Parameter Sheet", false); // non-modal
        this.modelSupplier = modelSupplier;
        this.textEditor = textEditor;
        this.tableModel = new ParameterSheetTableModel();

        initUI();
        loadData();
        listenForExternalEdits();

        setMinimumSize(new Dimension(700, 400));
        setSize(1000, 600);
        setLocationRelativeTo(parent);
    }

    private void initUI() {
        setLayout(new BorderLayout(0, 4));

        // --- Filter panel ---
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        filterPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));

        filterPanel.add(new JLabel("Type:"));
        typeFilterCombo = new JComboBox<>();
        typeFilterCombo.setPreferredSize(new Dimension(160, 24));
        typeFilterCombo.addActionListener(e -> applyFilters());
        filterPanel.add(typeFilterCombo);

        filterPanel.add(new JLabel("Name:"));
        nameFilterField = new JTextField(16);
        nameFilterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilters(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilters(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilters(); }
        });
        filterPanel.add(nameFilterField);

        add(filterPanel, BorderLayout.NORTH);

        // --- Table ---
        table = new JTable(tableModel);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new DirtyCellRenderer());
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        // Sorting by column header click, with numeric-aware comparison
        sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);
        updateSorterComparators();

        // Grid lines and row height (matching TableView style)
        table.setRowHeight(UIConstants.TableView.ROW_HEIGHT);
        table.setShowGrid(true);
        table.setIntercellSpacing(new Dimension(1, 1)); // FlatLaf defaults to 0,0 which hides grid lines
        Color gridColor = UIManager.getColor("Table.gridColor");
        table.setGridColor(gridColor != null ? gridColor : UIConstants.TableView.FALLBACK_GRID_COLOR);

        // Cell-level selection for copy/paste without needing to enter edit mode
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setCellSelectionEnabled(true);

        setupCopyPaste();

        JScrollPane scrollPane = new JScrollPane(table,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        add(scrollPane, BorderLayout.CENTER);

        // --- Button panel ---
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));

        okButton = new JButton("OK");
        okButton.addActionListener(e -> applyChanges());

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        add(buttonPanel, BorderLayout.SOUTH);

        // Cleanup on close
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dispose();
            }
        });
    }

    private void loadData() {
        INIModelParser.ParsedModel model = modelSupplier.get();
        if (model == null) {
            return;
        }
        allNodes = model.getNodes();

        // Populate type filter
        typeFilterCombo.removeAllItems();
        typeFilterCombo.addItem(""); // all types
        for (String type : ParameterSheetTableModel.collectNodeTypes(allNodes)) {
            typeFilterCombo.addItem(type);
        }

        applyFilters();
    }

    private void applyFilters() {
        if (allNodes == null) return;

        // Stop any active cell editing before rebuilding
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }

        String typeFilter = (String) typeFilterCombo.getSelectedItem();
        String namePattern = nameFilterField.getText();

        tableModel.populate(allNodes, typeFilter, namePattern);
        autoSizeColumns();
        updateSorterComparators();
    }

    private void autoSizeColumns() {
        FontMetrics fm = table.getFontMetrics(table.getFont());
        FontMetrics headerFm = table.getTableHeader().getFontMetrics(table.getTableHeader().getFont());

        for (int col = 0; col < table.getColumnCount(); col++) {
            TableColumn column = table.getColumnModel().getColumn(col);

            // Start with header width
            int width = headerFm.stringWidth(table.getColumnName(col)) + COLUMN_PADDING;

            // Check data widths
            for (int row = 0; row < table.getRowCount(); row++) {
                Object value = table.getValueAt(row, col);
                if (value != null) {
                    int cellWidth = fm.stringWidth(value.toString()) + COLUMN_PADDING;
                    width = Math.max(width, cellWidth);
                }
            }

            // Clamp to min/max
            width = Math.max(MIN_COLUMN_WIDTH, Math.min(width, MAX_COLUMN_WIDTH));
            column.setPreferredWidth(width);
        }
    }

    /**
     * Sets a numeric-aware comparator on all columns except the node name column.
     * Values that parse as numbers are compared numerically; others fall back to string comparison.
     * Called after each filter change since columns may be rebuilt.
     */
    private void updateSorterComparators() {
        Comparator<Object> numericAware = (a, b) -> {
            String sa = a != null ? a.toString().trim() : "";
            String sb = b != null ? b.toString().trim() : "";
            Double da = tryParseDouble(sa);
            Double db = tryParseDouble(sb);
            if (da != null && db != null) {
                return Double.compare(da, db);
            }
            // Empty strings sort last
            if (sa.isEmpty() && !sb.isEmpty()) return 1;
            if (!sa.isEmpty() && sb.isEmpty()) return -1;
            return sa.compareToIgnoreCase(sb);
        };

        for (int col = 1; col < tableModel.getColumnCount(); col++) {
            sorter.setComparator(col, numericAware);
        }
    }

    private static Double tryParseDouble(String s) {
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void setupCopyPaste() {
        int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        InputMap inputMap = table.getInputMap(JTable.WHEN_FOCUSED);
        ActionMap actionMap = table.getActionMap();

        // Copy: Cmd+C / Ctrl+C
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcutMask), "copy-cells");
        actionMap.put("copy-cells", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copySelectedCells();
            }
        });

        // Paste: Cmd+V / Ctrl+V
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcutMask), "paste-cells");
        actionMap.put("paste-cells", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pasteFromClipboard();
            }
        });
    }

    private void copySelectedCells() {
        int[] selectedRows = table.getSelectedRows();
        int[] selectedCols = table.getSelectedColumns();
        if (selectedRows.length == 0 || selectedCols.length == 0) return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selectedRows.length; i++) {
            for (int j = 0; j < selectedCols.length; j++) {
                Object value = table.getValueAt(selectedRows[i], selectedCols[j]);
                sb.append(value != null ? value.toString() : "");
                if (j < selectedCols.length - 1) sb.append("\t");
            }
            if (i < selectedRows.length - 1) sb.append("\n");
        }

        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(sb.toString()), null);
    }

    private void pasteFromClipboard() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            String data = (String) clipboard.getData(DataFlavor.stringFlavor);
            if (data == null || data.isEmpty()) return;

            int[] selectedRows = table.getSelectedRows();
            int[] selectedCols = table.getSelectedColumns();
            if (selectedRows.length == 0 || selectedCols.length == 0) return;

            // Detect delimiter: tab-separated (from spreadsheets) or comma-separated
            String delimiter = data.contains("\t") ? "\t" : ",";
            String[] rows = data.split("\r?\n");

            // Single-value clipboard: fill all selected cells with that value
            boolean singleValue = rows.length == 1 && rows[0].split(delimiter, -1).length == 1;
            if (singleValue) {
                String value = rows[0].trim();
                for (int row : selectedRows) {
                    for (int col : selectedCols) {
                        if (table.isCellEditable(row, col)) {
                            table.setValueAt(value, row, col);
                        }
                    }
                }
                return;
            }

            // Multi-value clipboard: paste starting at top-left selected cell
            int startRow = selectedRows[0];
            int startCol = selectedCols[0];
            for (int i = 0; i < rows.length && (startRow + i) < table.getRowCount(); i++) {
                String[] cells = rows[i].split(delimiter, -1);
                for (int j = 0; j < cells.length && (startCol + j) < table.getColumnCount(); j++) {
                    int row = startRow + i;
                    int col = startCol + j;
                    if (table.isCellEditable(row, col)) {
                        table.setValueAt(cells[j].trim(), row, col);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Paste failed: {}", e.getMessage());
        }
    }

    private void listenForExternalEdits() {
        externalEditListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onExternalEdit(); }
            @Override public void removeUpdate(DocumentEvent e) { onExternalEdit(); }
            @Override public void changedUpdate(DocumentEvent e) { onExternalEdit(); }
        };
        textEditor.addDocumentListener(externalEditListener);
    }

    private void onExternalEdit() {
        if (!externalEditDetected) {
            externalEditDetected = true;
            SwingUtilities.invokeLater(() -> {
                okButton.setEnabled(false);
                okButton.setToolTipText("Model was modified externally. Close and reopen the Parameter Sheet.");
            });
        }
    }

    private void applyChanges() {
        if (externalEditDetected) {
            return;
        }

        List<ParameterSheetTableModel.CellChange> changes = tableModel.getDirtyChanges();
        if (changes.isEmpty()) {
            dispose();
            return;
        }

        // Re-parse the model to guard against race conditions between the OK click
        // and the actual application of changes
        INIModelParser.ParsedModel currentModel = modelSupplier.get();
        if (currentModel == null) {
            JOptionPane.showMessageDialog(this,
                    "Cannot apply changes: model could not be parsed.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        for (ParameterSheetTableModel.CellChange change : changes) {
            if (change.originalLineNumber > 0) {
                INIModelParser.Section section = currentModel.getSections().get(change.sectionName);
                if (section == null) {
                    showStaleModelError(change);
                    return;
                }
                INIModelParser.Property currentProp = section.getProperties().get(change.propertyKey);
                if (currentProp == null || currentProp.getLineNumber() != change.originalLineNumber) {
                    showStaleModelError(change);
                    return;
                }
            }
        }

        // Separate into updates (property already exists) and potential additions
        List<ParameterSheetTableModel.CellChange> updates = new ArrayList<>();
        List<ParameterSheetTableModel.CellChange> additions = new ArrayList<>();

        for (ParameterSheetTableModel.CellChange change : changes) {
            if (change.originalLineNumber > 0) {
                updates.add(change);
            } else {
                // Property didn't exist on this node — would need to be inserted
                // For now, skip additions of empty values (user cleared a cell that was already empty)
                if (!change.newValue.isEmpty()) {
                    additions.add(change);
                }
            }
        }

        // Apply updates using CommandExecutor's replacePropertyValue pattern
        if (!updates.isEmpty() || !additions.isEmpty()) {
            try {
                applyUpdatesAndAdditions(updates, additions);
            } catch (Exception e) {
                logger.error("Error applying parameter sheet changes", e);
                JOptionPane.showMessageDialog(this,
                        "Error applying changes: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        dispose();
    }

    private void applyUpdatesAndAdditions(List<ParameterSheetTableModel.CellChange> updates,
                                           List<ParameterSheetTableModel.CellChange> additions) {
        String text = textEditor.getText();
        String[] lines = text.split("\n", -1);

        // Collect all replacements
        List<EnhancedTextEditor.LineReplacement> replacements = new ArrayList<>();

        // Process updates: replace existing property values
        for (ParameterSheetTableModel.CellChange change : updates) {
            int lineIndex = change.originalLineNumber - 1;
            if (lineIndex < 0 || lineIndex >= lines.length) {
                logger.warn("Invalid line number {} for property {} on node {}",
                        change.originalLineNumber, change.propertyKey, change.nodeName);
                continue;
            }

            String newLine = change.propertyKey + " = " + change.newValue;

            replacements.add(new EnhancedTextEditor.LineReplacement(
                    change.originalLineNumber,
                    lines[lineIndex],
                    newLine
            ));
        }

        // If we have additions or multi-line properties, fall back to full text reconstruction
        if (!additions.isEmpty() || hasMultiLineUpdates(updates, lines)) {
            applyAsFullTextReconstruction(updates, additions);
            return;
        }

        // Apply simple single-line replacements atomically
        if (!replacements.isEmpty()) {
            textEditor.applyAtomicReplacements(replacements);
        }
    }

    private boolean hasMultiLineUpdates(List<ParameterSheetTableModel.CellChange> updates, String[] lines) {
        for (ParameterSheetTableModel.CellChange change : updates) {
            int lineIndex = change.originalLineNumber - 1;
            if (lineIndex < 0 || lineIndex >= lines.length) continue;
            for (int i = lineIndex + 1; i < lines.length; i++) {
                String nextLine = lines[i];
                if (!nextLine.isEmpty() && Character.isWhitespace(nextLine.charAt(0))) {
                    return true;
                } else {
                    break;
                }
            }
        }
        return false;
    }

    /**
     * Applies changes by reconstructing the full text. Used when changes involve
     * multi-line properties or property additions that can't be expressed as
     * simple line replacements.
     */
    private void applyAsFullTextReconstruction(List<ParameterSheetTableModel.CellChange> updates,
                                                List<ParameterSheetTableModel.CellChange> additions) {
        String text = textEditor.getText();
        String[] lines = text.split("\n", -1);

        // Mark lines for deletion (continuation lines of updated multi-line properties)
        boolean[] deleteLines = new boolean[lines.length];

        // Process updates
        for (ParameterSheetTableModel.CellChange change : updates) {
            int lineIndex = change.originalLineNumber - 1;
            if (lineIndex < 0 || lineIndex >= lines.length) continue;

            // Replace the property line
            lines[lineIndex] = change.propertyKey + " = " + change.newValue;

            // Mark continuation lines for deletion
            for (int i = lineIndex + 1; i < lines.length; i++) {
                String nextLine = lines[i];
                if (!nextLine.isEmpty() && Character.isWhitespace(nextLine.charAt(0))) {
                    deleteLines[i] = true;
                } else {
                    break;
                }
            }
        }

        // Build lines to insert (additions grouped by section)
        Map<String, List<String>> insertions = new LinkedHashMap<>();
        for (ParameterSheetTableModel.CellChange change : additions) {
            String newLine = change.propertyKey + " = " + change.newValue;
            insertions.computeIfAbsent(change.sectionName, k -> new ArrayList<>()).add(newLine);
        }

        // Reconstruct text
        StringBuilder sb = new StringBuilder();
        String currentSection = null;
        Pattern sectionPattern = Pattern.compile("^\\s*\\[([^\\]]+)]\\s*$");

        for (int i = 0; i < lines.length; i++) {
            if (deleteLines[i]) continue;

            // Track current section
            Matcher m = sectionPattern.matcher(lines[i]);
            if (m.matches()) {
                // Before moving to next section, flush any insertions for current section
                if (currentSection != null && insertions.containsKey(currentSection)) {
                    for (String insertion : insertions.remove(currentSection)) {
                        sb.append(insertion).append("\n");
                    }
                }
                currentSection = m.group(1).trim();
            }

            sb.append(lines[i]);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }

        // Flush any remaining insertions (for the last section)
        if (currentSection != null && insertions.containsKey(currentSection)) {
            for (String insertion : insertions.remove(currentSection)) {
                sb.append("\n").append(insertion);
            }
        }

        // Apply as atomic edit
        String newText = sb.toString();
        SwingUtilities.invokeLater(() -> {
            try {
                Document doc = textEditor.getTextArea().getDocument();
                textEditor.getTextArea().beginAtomicEdit();
                try {
                    doc.remove(0, doc.getLength());
                    doc.insertString(0, newText, null);
                } finally {
                    textEditor.getTextArea().endAtomicEdit();
                }
            } catch (Exception e) {
                logger.error("Error applying full text reconstruction", e);
            }
        });
    }

    private void showStaleModelError(ParameterSheetTableModel.CellChange change) {
        JOptionPane.showMessageDialog(this,
                "The model has changed since the Parameter Sheet was opened.\n"
                + "Property '" + change.propertyKey + "' on node '" + change.nodeName
                + "' is no longer at the expected location.\n\n"
                + "Please close and reopen the Parameter Sheet.",
                "Model Changed", JOptionPane.WARNING_MESSAGE);
    }

    @Override
    public void dispose() {
        // Stop any active cell editing
        if (table != null && table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        // Remove our document listener to prevent memory leak
        if (externalEditListener != null) {
            textEditor.removeDocumentListener(externalEditListener);
            externalEditListener = null;
        }
        super.dispose();
    }

    // --- Cell renderer that highlights dirty cells ---

    private class DirtyCellRenderer extends DefaultTableCellRenderer {

        private static final Color DIRTY_BACKGROUND = new Color(255, 255, 200);
        private static final Color NAME_BACKGROUND = new Color(240, 240, 240);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                        boolean isSelected, boolean hasFocus,
                                                        int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if (!isSelected) {
                int modelRow = table.convertRowIndexToModel(row);
                if (column == 0) {
                    c.setBackground(NAME_BACKGROUND);
                } else if (tableModel.isCellDirty(modelRow, column)) {
                    c.setBackground(DIRTY_BACKGROUND);
                } else {
                    c.setBackground(Color.WHITE);
                }
            }

            return c;
        }
    }
}
