package com.kalix.ide.editor.commands;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.tableview.TablePropertyDefinition;
import com.kalix.ide.tableview.TablePropertyRegistry;
import com.kalix.ide.tableview.TableViewWindow;
import com.kalix.ide.tableview.definitions.LookupTable1dDefinition;
import com.kalix.ide.tableview.definitions.LookupTable2dDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.KeyStroke;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Supplier;

/**
 * Command to open a table view for editing supported properties.
 * Supports properties like Sacramento params, GR4J params, and Storage dimensions.
 */
public class OpenTableViewCommand implements EditorCommand {

    /** Command id, exposed so keyboard shortcuts can dispatch by id. */
    public static final String COMMAND_ID = "open_table_view";

    private static final Logger logger = LoggerFactory.getLogger(OpenTableViewCommand.class);

    private final CommandMetadata metadata;
    private final JFrame parentFrame;
    private final Supplier<INIModelParser.ParsedModel> modelSupplier;

    public OpenTableViewCommand(JFrame parentFrame, Supplier<INIModelParser.ParsedModel> modelSupplier) {
        this.parentFrame = parentFrame;
        this.modelSupplier = modelSupplier;
        this.metadata = new CommandMetadata.Builder()
            .id(COMMAND_ID)
            .displayName("Table View")
            .description("Edit this property in a table view")
            .category("")
            // Cmd+T on macOS, Ctrl+T on Windows/Linux. Mirrors the actual
            // keybinding in EnhancedTextEditor.setupKeyBindings; the menu
            // builder reads this back via getShortcutHint() to show a hint.
            .keyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_T, menuShortcutMask()))
            .build();
    }

    /**
     * Headless (CI test) environments have no toolkit; the fallback mask is
     * never shown to a user because the real IDE always has a display.
     */
    private static int menuShortcutMask() {
        return GraphicsEnvironment.isHeadless()
                ? InputEvent.CTRL_DOWN_MASK
                : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    @Override
    public CommandMetadata getMetadata() {
        return metadata;
    }

    @Override
    public boolean isApplicable(EditorContext context) {
        if (context.getType() != EditorContext.ContextType.PROPERTY) {
            return false;
        }
        String propertyKey = context.getPropertyKey().orElse(null);

        // Lookup table sections: the values property is always table-editable
        // (mirrors the accept-any-value behaviour of the node-table definitions).
        if (isLookupTableSection(context)) {
            return "values".equals(propertyKey);
        }

        String nodeType = context.getNodeType().orElse(null);
        String value = context.getPropertyValue().orElse("");
        return TablePropertyRegistry.getInstance().findHandler(nodeType, propertyKey, value) != null;
    }

    @Override
    public void execute(EditorContext context, CommandExecutor executor) {
        if (isLookupTableSection(context)) {
            executeForLookupTable(context, executor);
            return;
        }

        String nodeType = context.getNodeType().orElse(null);
        String propertyKey = context.getPropertyKey().orElse(null);
        String nodeName = context.getNodeName().orElse(null);

        if (nodeType == null || propertyKey == null || nodeName == null) {
            logger.warn("Missing context information for table view");
            return;
        }

        INIModelParser.Property property = findProperty("node." + nodeName, propertyKey);
        if (property == null) {
            return;
        }

        String currentValue = property.getValue();

        // Resolve the definition against the live value, mirroring isApplicable.
        // Re-checking here is defensive against the (unlikely) race in which the
        // value has changed since the menu was shown.
        TablePropertyDefinition definition = TablePropertyRegistry.getInstance()
                .findHandler(nodeType, propertyKey, currentValue);
        if (definition == null) {
            logger.warn("No table definition handles {}:{} for current value", nodeType, propertyKey);
            return;
        }

        showWindowAndApply(definition, property, nodeName, executor);
    }

    /**
     * Table view for a [table.*] section's values property. The definition is
     * constructed per invocation rather than fetched from the registry: a
     * lookup table's shape is declared by its sibling n_cols property
     * (n_cols > 2 means a 2D grid with a key row), which the value string
     * alone cannot supply.
     */
    private void executeForLookupTable(EditorContext context, CommandExecutor executor) {
        String sectionName = context.getSectionName().orElse(null);
        String propertyKey = context.getPropertyKey().orElse(null);
        if (sectionName == null || !"values".equals(propertyKey)) {
            logger.warn("Missing context information for lookup table view");
            return;
        }

        INIModelParser.Property property = findProperty(sectionName, propertyKey);
        if (property == null) {
            return;
        }

        String tableName = sectionName.substring("table.".length());
        int nCols = readNCols(sectionName);
        TablePropertyDefinition definition = nCols > 2
                ? new LookupTable2dDefinition(tableName, nCols)
                : new LookupTable1dDefinition(tableName, property.getValue());

        showWindowAndApply(definition, property, tableName, executor);
    }

    private boolean isLookupTableSection(EditorContext context) {
        return context.getSectionName().map(s -> s.startsWith("table.")).orElse(false);
    }

    /**
     * Reads the section's n_cols property, defaulting to 2 (a 1D table) when
     * absent or malformed — matching the engine's default. A malformed n_cols
     * is the linter's diagnostic to make; the table view just falls back.
     */
    private int readNCols(String sectionName) {
        INIModelParser.ParsedModel model = modelSupplier.get();
        if (model == null) {
            return 2;
        }
        INIModelParser.Section section = model.getSections().get(sectionName);
        if (section == null) {
            return 2;
        }
        INIModelParser.Property nCols = section.getProperties().get("n_cols");
        if (nCols == null) {
            return 2;
        }
        try {
            return Integer.parseInt(nCols.getValue().trim());
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    /**
     * Resolves a property from the parsed model, logging on each missing link.
     */
    private INIModelParser.Property findProperty(String sectionName, String propertyKey) {
        INIModelParser.ParsedModel model = modelSupplier.get();
        if (model == null) {
            logger.warn("Could not get parsed model");
            return null;
        }
        INIModelParser.Section section = model.getSections().get(sectionName);
        if (section == null) {
            logger.warn("Section not found: {}", sectionName);
            return null;
        }
        INIModelParser.Property property = section.getProperties().get(propertyKey);
        if (property == null) {
            logger.warn("Property not found: {}", propertyKey);
        }
        return property;
    }

    /**
     * Shows the table view dialog and, on accept, writes the new value back
     * into the document. Shared by the node-table and lookup-table paths.
     */
    private void showWindowAndApply(TablePropertyDefinition definition,
                                    INIModelParser.Property property,
                                    String displayName,
                                    CommandExecutor executor) {
        String currentValue = property.getValue();
        TableViewWindow window = new TableViewWindow(parentFrame, definition, currentValue, displayName);
        String newValue = window.showAndGetResult();

        if (newValue != null) {
            // Always update when user clicks Accept - they may want to change
            // formatting even if the parsed values are the same.
            boolean success = executor.replacePropertyValue(
                    property.getKey(), currentValue, newValue, property.getLineNumber());
            if (success) {
                logger.info("Updated {} property via table view", property.getKey());
            }
        }
    }
}
