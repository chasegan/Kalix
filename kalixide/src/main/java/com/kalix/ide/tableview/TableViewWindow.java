package com.kalix.ide.tableview;

import com.kalix.ide.constants.AppConstants;
import com.kalix.ide.constants.UIConstants;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

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
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
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

        // Layout
        JPanel contentPanel = new JPanel(new BorderLayout(5, 5));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        contentPanel.add(createToolBar(), BorderLayout.NORTH);
        contentPanel.add(scrollPane, BorderLayout.CENTER);
        contentPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(contentPanel, BorderLayout.CENTER);
    }

    private void configureTable() {
        table.setRowHeight(UIConstants.TableView.ROW_HEIGHT);
        table.getTableHeader().setReorderingAllowed(false);

        // For VERTICAL orientation, set column widths
        if (definition.getOrientation() == DisplayOrientation.VERTICAL) {
            table.getColumnModel().getColumn(0).setPreferredWidth(UIConstants.TableView.PARAM_NAME_COLUMN_WIDTH);
            table.getColumnModel().getColumn(1).setPreferredWidth(UIConstants.TableView.VALUE_COLUMN_WIDTH);
        } else {
            // HORIZONTAL - all columns same width
            for (int i = 0; i < table.getColumnCount(); i++) {
                table.getColumnModel().getColumn(i).setPreferredWidth(UIConstants.TableView.DATA_COLUMN_WIDTH);
            }
        }

        // Enable multi-cell selection (click and drag)
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setCellSelectionEnabled(true);

        // Add faint grid lines for better cell visibility
        table.setShowGrid(true);
        Color gridColor = UIManager.getColor("Table.gridColor");
        if (gridColor == null) {
            gridColor = UIConstants.TableView.FALLBACK_GRID_COLOR;
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

        JMenuItem copyAllItem = new JMenuItem("Copy Entire Table");
        copyAllItem.addActionListener(e -> copyEntireTable());
        popupMenu.add(copyAllItem);

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
     * Copies the entire table including headers to clipboard in tab-separated format.
     */
    private void copyEntireTable() {
        StringBuilder sb = new StringBuilder();

        // Add column headers
        for (int col = 0; col < table.getColumnCount(); col++) {
            sb.append(table.getColumnName(col));
            if (col < table.getColumnCount() - 1) {
                sb.append("\t");
            }
        }
        sb.append("\n");

        // Add all data rows
        for (int row = 0; row < table.getRowCount(); row++) {
            for (int col = 0; col < table.getColumnCount(); col++) {
                Object value = table.getValueAt(row, col);
                sb.append(value != null ? value.toString() : "");
                if (col < table.getColumnCount() - 1) {
                    sb.append("\t");
                }
            }
            if (row < table.getRowCount() - 1) {
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
            width = UIConstants.TableView.VERTICAL_TABLE_WIDTH;
            height = Math.min(UIConstants.TableView.MAX_TABLE_HEIGHT,
                50 + tableModel.getRowCount() * UIConstants.TableView.ROW_HEIGHT);
        } else {
            width = UIConstants.TableView.HORIZONTAL_TABLE_WIDTH;
            height = Math.min(400, 50 + tableModel.getRowCount() * UIConstants.TableView.ROW_HEIGHT);
        }

        return new Dimension(width, Math.max(UIConstants.TableView.MIN_TABLE_HEIGHT, height));
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

    private JToolBar createToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setRollover(true);

        // Row management buttons (only for dynamic tables)
        if (!definition.isFixedRowCount()) {
            JButton addRowButton = createToolBarButton(
                "Add Row",
                FontAwesomeSolid.PLUS_SQUARE,
                e -> {
                    tableModel.addRow();
                    table.scrollRectToVisible(table.getCellRect(tableModel.getRowCount() - 1, 0, true));
                }
            );
            toolBar.add(addRowButton);

            JButton removeRowButton = createToolBarButton(
                "Remove Row",
                FontAwesomeSolid.MINUS_SQUARE,
                e -> {
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
                }
            );
            toolBar.add(removeRowButton);

            toolBar.addSeparator();
        }

        // Generate pyramidal dimensions button (only for storage dimensions)
        if ("storage".equals(definition.getNodeType()) && "dimensions".equals(definition.getPropertyName())) {
            JButton generateButton = createToolBarButton(
                "Generate Pyramidal Dimensions",
                FontAwesomeSolid.MAGIC,
                e -> generatePyramidalDimensions()
            );
            toolBar.add(generateButton);
            toolBar.addSeparator();
        }

        // Format values button (trim to significant figures)
        JButton formatButton = createToolBarButton(
            "Format pretty",
            FontAwesomeSolid.HAND_SPARKLES,
            e -> formatAllValues()
        );
        toolBar.add(formatButton);

        return toolBar;
    }

    /**
     * Prompts for parameters and generates pyramidal storage dimensions.
     */
    private void generatePyramidalDimensions() {
        // Create panel with both input fields
        JPanel panel = new JPanel(new BorderLayout(5, 5));

        JPanel labelsPanel = new JPanel(new java.awt.GridLayout(2, 1, 5, 5));
        labelsPanel.add(new javax.swing.JLabel("Full supply volume [ML]:"));
        labelsPanel.add(new javax.swing.JLabel("Full supply area [km²]:"));

        JPanel fieldsPanel = new JPanel(new java.awt.GridLayout(2, 1, 5, 5));
        javax.swing.JTextField volumeField = new javax.swing.JTextField("10000", 15);
        javax.swing.JTextField areaField = new javax.swing.JTextField("3.0", 15);
        fieldsPanel.add(volumeField);
        fieldsPanel.add(areaField);

        panel.add(labelsPanel, BorderLayout.WEST);
        panel.add(fieldsPanel, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(this, panel,
            "Generate Pyramidal Dimensions",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) {
            return; // Cancelled
        }

        try {
            double fsVolume = Double.parseDouble(volumeField.getText().trim());
            double fsArea = Double.parseDouble(areaField.getText().trim());

            // Generate the pyramidal dimensions
            double[][] rows = PyramidalDimensionsCalculator.generateRows(fsVolume, fsArea);

            // Clear existing rows and populate with new data
            while (tableModel.getRowCount() > 0) {
                tableModel.removeRow(0);
            }

            for (double[] row : rows) {
                tableModel.addRow();
                int rowIndex = tableModel.getRowCount() - 1;
                tableModel.setValueAt(Double.toString(row[0]), rowIndex, 0); // Level
                tableModel.setValueAt(Double.toString(row[1]), rowIndex, 1); // Volume
                tableModel.setValueAt(Double.toString(row[2]), rowIndex, 2); // Area
                tableModel.setValueAt(Double.toString(row[3]), rowIndex, 3); // Spill
            }

        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this,
                "Invalid numeric values entered",
                "Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Formats all numeric values to 3 significant figures with at least 3 decimal places.
     * Formula: N decimal places where N = 2 + max(1, ceil(-log10(value)))
     */
    private void formatAllValues() {
        // Stop any active editing
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }

        int valueColStart = definition.getOrientation() == DisplayOrientation.VERTICAL ? 1 : 0;

        for (int row = 0; row < tableModel.getRowCount(); row++) {
            for (int col = valueColStart; col < tableModel.getColumnCount(); col++) {
                String value = (String) tableModel.getValueAt(row, col);
                if (value != null && !value.trim().isEmpty()) {
                    String formatted = formatToSignificantFigures(value.trim());
                    if (formatted != null) {
                        tableModel.setValueAt(formatted, row, col);
                    }
                }
            }
        }
    }

    /**
     * Formats a numeric string to 3 significant figures.
     * - Scientific notation inputs stay in scientific notation with 3 sig figs
     * - Decimal inputs get at least 3 decimal places using formula: N = 2 + max(1, ceil(-log10(|value|)))
     * @param value The numeric string to format
     * @return The formatted string, or null if not a valid number
     */
    private String formatToSignificantFigures(String value) {
        try {
            double num = Double.parseDouble(value);
            if (num == 0) {
                return "0.000";
            }

            // Check if original value is in scientific notation
            boolean isScientific = value.contains("e") || value.contains("E");

            if (isScientific) {
                // Format to 3 significant figures in scientific notation (1 + 2 decimal places)
                return String.format("%.2e", num);
            } else {
                // N = 2 + max(1, ceil(-log10(|value|)))
                int n = 2 + Math.max(1, (int) Math.ceil(-Math.log10(Math.abs(num))));

                // Format with n decimal places
                return String.format("%." + n + "f", num);
            }
        } catch (NumberFormatException e) {
            return null; // Not a valid number, leave unchanged
        }
    }

    private JButton createToolBarButton(String tooltip, FontAwesomeSolid iconType,
                                         java.awt.event.ActionListener listener) {
        FontIcon icon = FontIcon.of(iconType, AppConstants.TOOLBAR_ICON_SIZE);
        icon.setIconColor(getThemeAwareIconColor());

        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        button.addActionListener(listener);
        button.getAccessibleContext().setAccessibleName(tooltip);

        return button;
    }

    private Color getThemeAwareIconColor() {
        Color toolbarBackground = UIManager.getColor("ToolBar.background");
        if (toolbarBackground != null) {
            int sum = toolbarBackground.getRed() + toolbarBackground.getGreen() + toolbarBackground.getBlue();
            boolean isDarkTheme = sum < 384;
            return isDarkTheme ? Color.LIGHT_GRAY : Color.DARK_GRAY;
        }
        return Color.DARK_GRAY;
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

}
