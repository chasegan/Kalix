package com.kalix.ide.tableview;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog window for editing table-based property values.
 * Supports both VERTICAL (parameter list) and HORIZONTAL (data table) orientations.
 */
public class TableViewWindow extends JDialog {

    private final TablePropertyDefinition definition;
    private final JTable table;
    private final TableDataModel tableModel;
    private final TableValueFormatter formatter;
    private final String originalValue;
    private final int continuationIndent;
    private String result = null;
    private boolean accepted = false;
    private boolean multiLineFormat = false;

    public TableViewWindow(JFrame parent, TablePropertyDefinition definition, String currentValue, String nodeName) {
        super(parent, nodeName + " - " + definition.getPropertyName(), true);
        this.definition = definition;
        this.originalValue = currentValue;

        // Calculate continuation indent: property name + " = "
        this.continuationIndent = definition.getPropertyName().length() + 3;

        // Set up formatter and parse original comments
        this.formatter = new TableValueFormatter();
        formatter.parseOriginalComments(currentValue);

        // Parse current values
        String[][] values = definition.parseValues(currentValue);
        this.tableModel = new TableDataModel(definition, values);
        this.table = new JTable(tableModel);

        initializeUI();
        pack();
        setLocationRelativeTo(parent);
    }

    private void initializeUI() {
        setLayout(new BorderLayout(10, 10));

        // Configure table appearance
        configureTable();

        // Main table panel with scroll
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(calculatePreferredSize());

        // Button panel
        JPanel buttonPanel = createButtonPanel();

        // Add row buttons for dynamic tables
        JPanel topPanel = null;
        if (!definition.isFixedRowCount()) {
            topPanel = createRowManagementPanel();
        }

        // Layout
        JPanel contentPanel = new JPanel(new BorderLayout(5, 5));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        if (topPanel != null) {
            contentPanel.add(topPanel, BorderLayout.NORTH);
        }
        contentPanel.add(scrollPane, BorderLayout.CENTER);
        contentPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(contentPanel, BorderLayout.CENTER);
    }

    private void configureTable() {
        table.setRowHeight(24);
        table.getTableHeader().setReorderingAllowed(false);

        // For VERTICAL orientation, set column widths
        if (definition.getOrientation() == DisplayOrientation.VERTICAL) {
            table.getColumnModel().getColumn(0).setPreferredWidth(120);
            table.getColumnModel().getColumn(1).setPreferredWidth(150);
        } else {
            // HORIZONTAL - all columns same width
            for (int i = 0; i < table.getColumnCount(); i++) {
                table.getColumnModel().getColumn(i).setPreferredWidth(100);
            }
        }

        // Enable multi-cell selection (click and drag)
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setCellSelectionEnabled(true);

        // Add faint grid lines for better cell visibility
        table.setShowGrid(true);
        Color gridColor = UIManager.getColor("Table.gridColor");
        if (gridColor == null) {
            // Fallback: create a faint gray color
            gridColor = new Color(220, 220, 220);
        }
        table.setGridColor(gridColor);

        // Set up copy/paste keyboard shortcuts
        setupClipboardActions();

        // Set up right-click context menu
        setupContextMenu();

        // Set up auto-add rows for dynamic tables
        if (!definition.isFixedRowCount()) {
            setupAutoAddRows();
        }
    }

    /**
     * Sets up copy and paste actions for Excel compatibility.
     * Excel uses tab-separated values with newlines between rows.
     */
    private void setupClipboardActions() {
        // Copy action (Ctrl+C / Cmd+C)
        KeyStroke copyKey = KeyStroke.getKeyStroke(KeyEvent.VK_C,
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        table.getInputMap().put(copyKey, "copy");
        table.getActionMap().put("copy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copySelectedCells();
            }
        });

        // Paste action (Ctrl+V / Cmd+V)
        KeyStroke pasteKey = KeyStroke.getKeyStroke(KeyEvent.VK_V,
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        table.getInputMap().put(pasteKey, "paste");
        table.getActionMap().put("paste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pasteFromClipboard();
            }
        });
    }

    /**
     * Sets up right-click context menu for the table.
     */
    private void setupContextMenu() {
        JPopupMenu popupMenu = new JPopupMenu();

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.addActionListener(e -> copySelectedCells());
        popupMenu.add(copyItem);

        JMenuItem pasteItem = new JMenuItem("Paste");
        pasteItem.addActionListener(e -> pasteFromClipboard());
        popupMenu.add(pasteItem);

        // Only show insert options for tables with dynamic rows
        if (!definition.isFixedRowCount()) {
            popupMenu.addSeparator();

            JMenuItem insertAboveItem = new JMenuItem("Insert Above");
            insertAboveItem.addActionListener(e -> {
                int selectedRow = table.getSelectedRow();
                if (selectedRow >= 0) {
                    tableModel.insertRow(selectedRow);
                } else {
                    tableModel.insertRow(0);
                }
            });
            popupMenu.add(insertAboveItem);

            JMenuItem insertBelowItem = new JMenuItem("Insert Below");
            insertBelowItem.addActionListener(e -> {
                int selectedRow = table.getSelectedRow();
                if (selectedRow >= 0) {
                    tableModel.insertRow(selectedRow + 1);
                } else {
                    tableModel.addRow();
                }
            });
            popupMenu.add(insertBelowItem);

            JMenuItem deleteRowsItem = new JMenuItem("Delete Selected Row(s)");
            deleteRowsItem.addActionListener(e -> {
                int[] selectedRows = table.getSelectedRows();
                if (selectedRows.length == 0) {
                    return;
                }
                // Delete from bottom to top to avoid index shifting
                for (int i = selectedRows.length - 1; i >= 0; i--) {
                    if (tableModel.getRowCount() > 1) {
                        tableModel.removeRow(selectedRows[i]);
                    }
                }
            });
            popupMenu.add(deleteRowsItem);
        }

        // Select row under mouse before showing popup
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    selectRowAtPoint(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    selectRowAtPoint(e);
                }
            }

            private void selectRowAtPoint(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && !table.isRowSelected(row)) {
                    table.setRowSelectionInterval(row, row);
                }
            }
        });

        table.setComponentPopupMenu(popupMenu);
    }

    /**
     * Sets up automatic row addition when user navigates past the last row.
     */
    private void setupAutoAddRows() {
        // Override Down arrow behavior
        KeyStroke downKey = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0);
        table.getInputMap().put(downKey, "moveDownOrAddRow");
        table.getActionMap().put("moveDownOrAddRow", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int row = table.getSelectedRow();
                int col = table.getSelectedColumn();
                if (row == table.getRowCount() - 1) {
                    // At last row - add a new row and move to it
                    tableModel.addRow();
                }
                // Move to next row
                if (row < table.getRowCount() - 1) {
                    table.changeSelection(row + 1, col, false, false);
                }
            }
        });

        // Override Enter behavior (commits edit and moves down)
        KeyStroke enterKey = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
        table.getInputMap().put(enterKey, "commitAndMoveDownOrAddRow");
        table.getActionMap().put("commitAndMoveDownOrAddRow", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Stop any active editing first
                if (table.isEditing()) {
                    table.getCellEditor().stopCellEditing();
                }

                int row = table.getSelectedRow();
                int col = table.getSelectedColumn();
                if (row == table.getRowCount() - 1) {
                    // At last row - add a new row
                    tableModel.addRow();
                }
                // Move to next row
                if (row < table.getRowCount() - 1) {
                    table.changeSelection(row + 1, col, false, false);
                }
            }
        });
    }

    /**
     * Copies selected cells to clipboard in tab-separated format.
     */
    private void copySelectedCells() {
        int[] selectedRows = table.getSelectedRows();
        int[] selectedCols = table.getSelectedColumns();

        if (selectedRows.length == 0 || selectedCols.length == 0) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selectedRows.length; i++) {
            for (int j = 0; j < selectedCols.length; j++) {
                Object value = table.getValueAt(selectedRows[i], selectedCols[j]);
                sb.append(value != null ? value.toString() : "");
                if (j < selectedCols.length - 1) {
                    sb.append("\t");
                }
            }
            if (i < selectedRows.length - 1) {
                sb.append("\n");
            }
        }

        StringSelection selection = new StringSelection(sb.toString());
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(selection, selection);
    }

    /**
     * Pastes values from clipboard into table starting at selected cell.
     * Supports both tab-separated (Excel) and comma-separated (CSV) formats.
     */
    private void pasteFromClipboard() {
        int startRow = table.getSelectedRow();
        int startCol = table.getSelectedColumn();

        if (startRow < 0 || startCol < 0) {
            return;
        }

        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            String data = (String) clipboard.getData(DataFlavor.stringFlavor);

            if (data == null || data.isEmpty()) {
                return;
            }

            // Detect delimiter: use tab if present, otherwise comma
            String delimiter = data.contains("\t") ? "\t" : ",";

            // Split into rows (handle both \n and \r\n)
            String[] rows = data.split("\r?\n");

            for (int i = 0; i < rows.length; i++) {
                int targetRow = startRow + i;

                // Add rows as needed for dynamic tables
                while (targetRow >= table.getRowCount() && !definition.isFixedRowCount()) {
                    tableModel.addRow();
                }

                if (targetRow >= table.getRowCount()) {
                    break;
                }

                // Split into columns using detected delimiter
                String[] cols = rows[i].split(delimiter);

                for (int j = 0; j < cols.length; j++) {
                    int targetCol = startCol + j;
                    if (targetCol >= table.getColumnCount()) {
                        break;
                    }

                    // Only paste into editable cells
                    if (table.isCellEditable(targetRow, targetCol)) {
                        table.setValueAt(cols[j].trim(), targetRow, targetCol);
                    }
                }
            }
        } catch (Exception ex) {
            // Clipboard may not contain string data
        }
    }

    private Dimension calculatePreferredSize() {
        int width;
        int height;

        if (definition.getOrientation() == DisplayOrientation.VERTICAL) {
            width = 300;
            height = Math.min(500, 50 + tableModel.getRowCount() * 24);
        } else {
            width = 450;
            height = Math.min(400, 50 + tableModel.getRowCount() * 24);
        }

        return new Dimension(width, Math.max(200, height));
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            accepted = false;
            dispose();
        });

        JButton acceptInlineButton = new JButton("Accept (in-line)");
        acceptInlineButton.addActionListener(e -> {
            if (validateAllCells()) {
                accepted = true;
                multiLineFormat = false;
                result = formatResult();
                dispose();
            }
        });

        JButton acceptMultiLineButton = new JButton("Accept (multi-line)");
        acceptMultiLineButton.addActionListener(e -> {
            if (validateAllCells()) {
                accepted = true;
                multiLineFormat = true;
                result = formatResult();
                dispose();
            }
        });

        panel.add(cancelButton);
        panel.add(acceptInlineButton);
        panel.add(acceptMultiLineButton);

        // Set multi-line Accept as default button
        getRootPane().setDefaultButton(acceptMultiLineButton);

        return panel;
    }

    private JPanel createRowManagementPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton addRowButton = new JButton("Add Row");
        addRowButton.addActionListener(e -> {
            tableModel.addRow();
            table.scrollRectToVisible(table.getCellRect(tableModel.getRowCount() - 1, 0, true));
        });

        JButton removeRowButton = new JButton("Remove Row");
        removeRowButton.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0 && tableModel.getRowCount() > 1) {
                tableModel.removeRow(selectedRow);
            } else if (selectedRow < 0) {
                JOptionPane.showMessageDialog(this,
                    "Please select a row to remove",
                    "No Selection",
                    JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this,
                    "Cannot remove the last row",
                    "Cannot Remove",
                    JOptionPane.WARNING_MESSAGE);
            }
        });

        panel.add(addRowButton);
        panel.add(removeRowButton);

        return panel;
    }

    private boolean validateAllCells() {
        // Stop any active editing
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }

        StringBuilder errors = new StringBuilder();
        int errorCount = 0;

        for (int row = 0; row < tableModel.getRowCount(); row++) {
            // Skip empty rows (all cells empty or whitespace)
            if (isRowEmpty(row)) {
                continue;
            }

            int valueColStart = definition.getOrientation() == DisplayOrientation.VERTICAL ? 1 : 0;
            for (int col = valueColStart; col < tableModel.getColumnCount(); col++) {
                String value = (String) tableModel.getValueAt(row, col);
                int dataCol = definition.getOrientation() == DisplayOrientation.VERTICAL ? 0 : col;
                String error = definition.validateCell(row, dataCol, value);
                if (error != null) {
                    errorCount++;
                    if (errorCount <= 5) {
                        String rowLabel = definition.getOrientation() == DisplayOrientation.VERTICAL
                            ? definition.getRowNames()[row]
                            : "Row " + (row + 1);
                        errors.append("- ").append(rowLabel).append(": ").append(error).append("\n");
                    }
                }
            }
        }

        if (errorCount > 0) {
            String message = "Please fix the following errors:\n\n" + errors.toString();
            if (errorCount > 5) {
                message += "... and " + (errorCount - 5) + " more errors";
            }
            JOptionPane.showMessageDialog(this, message, "Validation Errors", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        return true;
    }

    private boolean isRowEmpty(int row) {
        int valueColStart = definition.getOrientation() == DisplayOrientation.VERTICAL ? 1 : 0;
        for (int col = valueColStart; col < tableModel.getColumnCount(); col++) {
            String value = (String) tableModel.getValueAt(row, col);
            if (value != null && !value.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String formatResult() {
        String[][] values = tableModel.getNonEmptyDataValues();
        if (multiLineFormat) {
            return formatter.formatMultiLine(
                values,
                definition.getOrientation(),
                definition.getValuesPerLine(),
                definition.getHeaderLine(),
                continuationIndent
            );
        } else {
            return formatter.formatInline(values, definition.getOrientation());
        }
    }

    /**
     * Shows the dialog and returns the formatted result if accepted.
     *
     * @return The formatted property value, or null if cancelled
     */
    public String showAndGetResult() {
        setVisible(true);
        return accepted ? result : null;
    }

    /**
     * Table model that handles both VERTICAL and HORIZONTAL orientations.
     */
    private static class TableDataModel extends AbstractTableModel {

        private final TablePropertyDefinition definition;
        private final List<String[]> data;
        private final String[] columnNames;

        public TableDataModel(TablePropertyDefinition definition, String[][] initialValues) {
            this.definition = definition;
            this.columnNames = definition.getColumnNames();

            // Convert initial values to mutable list
            this.data = new ArrayList<>();
            for (String[] row : initialValues) {
                data.add(row.clone());
            }
        }

        @Override
        public int getRowCount() {
            return data.size();
        }

        @Override
        public int getColumnCount() {
            if (definition.getOrientation() == DisplayOrientation.VERTICAL) {
                return 2; // Parameter name + value
            } else {
                return columnNames.length;
            }
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (definition.getOrientation() == DisplayOrientation.VERTICAL) {
                if (columnIndex == 0) {
                    // Parameter name
                    String[] rowNames = definition.getRowNames();
                    return rowIndex < rowNames.length ? rowNames[rowIndex] : "";
                } else {
                    // Value
                    return data.get(rowIndex)[0];
                }
            } else {
                return data.get(rowIndex)[columnIndex];
            }
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (definition.getOrientation() == DisplayOrientation.VERTICAL) {
                if (columnIndex == 1) {
                    data.get(rowIndex)[0] = (String) aValue;
                    fireTableCellUpdated(rowIndex, columnIndex);
                }
            } else {
                data.get(rowIndex)[columnIndex] = (String) aValue;
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            if (definition.getOrientation() == DisplayOrientation.VERTICAL) {
                return columnIndex == 1; // Only value column is editable
            }
            return true; // All cells editable in HORIZONTAL mode
        }

        public void addRow() {
            int numCols = definition.getOrientation() == DisplayOrientation.VERTICAL
                ? 1
                : definition.getColumnNames().length;
            String[] newRow = new String[numCols];
            for (int i = 0; i < numCols; i++) {
                newRow[i] = "";
            }
            data.add(newRow);
            fireTableRowsInserted(data.size() - 1, data.size() - 1);
        }

        public void insertRow(int rowIndex) {
            int numCols = definition.getOrientation() == DisplayOrientation.VERTICAL
                ? 1
                : definition.getColumnNames().length;
            String[] newRow = new String[numCols];
            for (int i = 0; i < numCols; i++) {
                newRow[i] = "";
            }
            if (rowIndex < 0) {
                rowIndex = 0;
            } else if (rowIndex > data.size()) {
                rowIndex = data.size();
            }
            data.add(rowIndex, newRow);
            fireTableRowsInserted(rowIndex, rowIndex);
        }

        public void removeRow(int rowIndex) {
            if (rowIndex >= 0 && rowIndex < data.size()) {
                data.remove(rowIndex);
                fireTableRowsDeleted(rowIndex, rowIndex);
            }
        }

        /**
         * Gets the data values in the format expected by the definition's formatValues method.
         */
        public String[][] getDataValues() {
            String[][] result = new String[data.size()][];
            for (int i = 0; i < data.size(); i++) {
                result[i] = data.get(i).clone();
            }
            return result;
        }

        /**
         * Gets non-empty data values, filtering out rows where all cells are empty or whitespace.
         */
        public String[][] getNonEmptyDataValues() {
            List<String[]> nonEmptyRows = new ArrayList<>();
            for (String[] row : data) {
                boolean hasValue = false;
                for (String cell : row) {
                    if (cell != null && !cell.trim().isEmpty()) {
                        hasValue = true;
                        break;
                    }
                }
                if (hasValue) {
                    nonEmptyRows.add(row.clone());
                }
            }
            return nonEmptyRows.toArray(new String[0][]);
        }
    }

}
