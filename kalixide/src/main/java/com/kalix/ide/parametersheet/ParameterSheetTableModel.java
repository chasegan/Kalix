package com.kalix.ide.parametersheet;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.parsing.INIModelParser.NodeSection;
import com.kalix.ide.linter.parsing.INIModelParser.Property;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Table model for the Parameter Sheet. Rows are nodes, columns are property keys.
 * Column 0 is always the node name (non-editable). Column 1 is the type (editable).
 * Remaining columns are the union of all property keys found across visible nodes.
 *
 * <p>Multi-line property values are inlined (continuation lines joined with spaces)
 * by the parser, so they display as single-line text in cells.</p>
 *
 * <p>Tracks which cells have been modified so that only dirty cells are written back.</p>
 */
public class ParameterSheetTableModel extends AbstractTableModel {

    private static final String NODE_NAME_COLUMN = "Node Name";
    private static final String TYPE_COLUMN = "type";

    private final List<NodeSection> visibleNodes = new ArrayList<>();
    private final List<String> columnKeys = new ArrayList<>(); // property keys in column order
    private final Map<CellKey, String> dirtyValues = new LinkedHashMap<>();

    // Snapshot of original values for dirty detection
    private final Map<CellKey, String> originalValues = new LinkedHashMap<>();

    /**
     * Populates the model from parsed node sections, applying optional filters.
     *
     * @param allNodes     all node sections from the parsed model
     * @param typeFilter   if non-null and non-empty, only show nodes of this type (case-insensitive)
     * @param namePattern  if non-null and non-empty, only show nodes whose name contains this (case-insensitive)
     */
    public void populate(Map<String, NodeSection> allNodes, String typeFilter, String namePattern) {
        visibleNodes.clear();
        columnKeys.clear();
        dirtyValues.clear();
        originalValues.clear();

        // Filter nodes
        for (NodeSection node : allNodes.values()) {
            if (typeFilter != null && !typeFilter.isEmpty()) {
                String nodeType = node.getNodeType();
                if (nodeType == null || !nodeType.equalsIgnoreCase(typeFilter)) {
                    continue;
                }
            }
            if (namePattern != null && !namePattern.isEmpty()) {
                if (!node.getNodeName().toLowerCase().contains(namePattern.toLowerCase())) {
                    continue;
                }
            }
            visibleNodes.add(node);
        }

        // Build column list from union of property keys across visible nodes (excluding "type")
        Set<String> keySet = new LinkedHashSet<>();
        for (NodeSection node : visibleNodes) {
            for (String key : node.getProperties().keySet()) {
                if (!TYPE_COLUMN.equalsIgnoreCase(key)) {
                    keySet.add(key);
                }
            }
        }
        columnKeys.addAll(keySet);

        // Store original values for dirty tracking
        for (int row = 0; row < visibleNodes.size(); row++) {
            NodeSection node = visibleNodes.get(row);
            // Type column
            originalValues.put(new CellKey(row, 1), node.getNodeType() != null ? node.getNodeType() : "");
            // Property columns
            for (int col = 0; col < columnKeys.size(); col++) {
                Property prop = node.getProperties().get(columnKeys.get(col));
                originalValues.put(new CellKey(row, col + 2), prop != null ? prop.getValue() : "");
            }
        }

        fireTableStructureChanged();
    }

    @Override
    public int getRowCount() {
        return visibleNodes.size();
    }

    @Override
    public int getColumnCount() {
        return 2 + columnKeys.size(); // node name + type + properties
    }

    @Override
    public String getColumnName(int column) {
        if (column == 0) return NODE_NAME_COLUMN;
        if (column == 1) return TYPE_COLUMN;
        return columnKeys.get(column - 2);
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return columnIndex >= 1; // node name is not editable, everything else is
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        // Check dirty values first
        CellKey key = new CellKey(rowIndex, columnIndex);
        if (dirtyValues.containsKey(key)) {
            return dirtyValues.get(key);
        }

        NodeSection node = visibleNodes.get(rowIndex);
        if (columnIndex == 0) {
            return node.getNodeName();
        }
        if (columnIndex == 1) {
            return node.getNodeType() != null ? node.getNodeType() : "";
        }

        String propKey = columnKeys.get(columnIndex - 2);
        Property prop = node.getProperties().get(propKey);
        return prop != null ? prop.getValue() : "";
    }

    @Override
    public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
        if (columnIndex < 1) return;

        String newValue = aValue != null ? aValue.toString() : "";
        CellKey key = new CellKey(rowIndex, columnIndex);
        String original = originalValues.getOrDefault(key, "");

        if (newValue.equals(original)) {
            // Reverted to original — remove from dirty set
            dirtyValues.remove(key);
        } else {
            dirtyValues.put(key, newValue);
        }

        fireTableCellUpdated(rowIndex, columnIndex);
    }

    /** Returns true if any cell has been modified. */
    public boolean hasDirtyValues() {
        return !dirtyValues.isEmpty();
    }

    /** Returns the dirty cells as a list of change records. */
    public List<CellChange> getDirtyChanges() {
        List<CellChange> changes = new ArrayList<>();
        for (Map.Entry<CellKey, String> entry : dirtyValues.entrySet()) {
            CellKey key = entry.getKey();
            NodeSection node = visibleNodes.get(key.row);
            int colIndex = key.col;

            String propertyKey;
            if (colIndex == 1) {
                propertyKey = TYPE_COLUMN;
            } else {
                propertyKey = columnKeys.get(colIndex - 2);
            }

            Property prop = node.getProperties().get(propertyKey);
            changes.add(new CellChange(
                    node.getNodeName(),
                    node.getName(), // section name e.g. "node.MyNode"
                    propertyKey,
                    prop != null ? prop.getLineNumber() : -1,
                    prop != null ? prop.getValue() : null,
                    entry.getValue()
            ));
        }
        return changes;
    }

    /** Returns the list of distinct node types present in the given nodes. */
    public static List<String> collectNodeTypes(Map<String, NodeSection> allNodes) {
        Set<String> types = new LinkedHashSet<>();
        for (NodeSection node : allNodes.values()) {
            if (node.getNodeType() != null && !node.getNodeType().isEmpty()) {
                types.add(node.getNodeType());
            }
        }
        List<String> sorted = new ArrayList<>(types);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    /** Returns the node sections currently visible in the table. */
    public List<NodeSection> getVisibleNodes() {
        return visibleNodes;
    }

    /** Returns the property keys currently used as columns (excluding name and type). */
    public List<String> getColumnKeys() {
        return columnKeys;
    }

    public boolean isCellDirty(int row, int col) {
        return dirtyValues.containsKey(new CellKey(row, col));
    }

    // --- Inner classes ---

    /** Identifies a cell by row and column index. */
    static final class CellKey {
        final int row;
        final int col;

        CellKey(int row, int col) {
            this.row = row;
            this.col = col;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CellKey)) return false;
            CellKey other = (CellKey) o;
            return row == other.row && col == other.col;
        }

        @Override
        public int hashCode() {
            return 31 * row + col;
        }
    }

    /** Describes a single cell change to be written back to the editor. */
    public static class CellChange {
        public final String nodeName;
        public final String sectionName;
        public final String propertyKey;
        public final int originalLineNumber; // -1 if property didn't exist
        public final String originalValue;   // null if property didn't exist
        public final String newValue;

        CellChange(String nodeName, String sectionName, String propertyKey,
                   int originalLineNumber, String originalValue, String newValue) {
            this.nodeName = nodeName;
            this.sectionName = sectionName;
            this.propertyKey = propertyKey;
            this.originalLineNumber = originalLineNumber;
            this.originalValue = originalValue;
            this.newValue = newValue;
        }
    }
}
