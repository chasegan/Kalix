package com.kalix.ide.editor.commands;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.tableview.TablePropertyDefinition;
import com.kalix.ide.tableview.TablePropertyRegistry;
import com.kalix.ide.tableview.TableViewWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.Toolkit;
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
            .keyboardShortcut(KeyStroke.getKeyStroke(
                    KeyEvent.VK_T, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()))
            .build();
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
        String nodeType = context.getNodeType().orElse(null);
        String propertyKey = context.getPropertyKey().orElse(null);
        String value = context.getPropertyValue().orElse("");
        return TablePropertyRegistry.getInstance().findHandler(nodeType, propertyKey, value) != null;
    }

    @Override
    public void execute(EditorContext context, CommandExecutor executor) {
        String nodeType = context.getNodeType().orElse(null);
        String propertyKey = context.getPropertyKey().orElse(null);
        String nodeName = context.getNodeName().orElse(null);

        if (nodeType == null || propertyKey == null || nodeName == null) {
            logger.warn("Missing context information for table view");
            return;
        }

        // Get current property value from parsed model
        INIModelParser.ParsedModel model = modelSupplier.get();
        if (model == null) {
            logger.warn("Could not get parsed model");
            return;
        }

        String sectionName = "node." + nodeName;
        INIModelParser.Section section = model.getSections().get(sectionName);
        if (section == null) {
            logger.warn("Section not found: {}", sectionName);
            return;
        }

        INIModelParser.Property property = section.getProperties().get(propertyKey);
        if (property == null) {
            logger.warn("Property not found: {}", propertyKey);
            return;
        }

        String currentValue = property.getValue();
        int propertyLineNumber = property.getLineNumber();

        // Resolve the definition against the live value, mirroring isApplicable.
        // Re-checking here is defensive against the (unlikely) race in which the
        // value has changed since the menu was shown.
        TablePropertyDefinition definition = TablePropertyRegistry.getInstance()
                .findHandler(nodeType, propertyKey, currentValue);
        if (definition == null) {
            logger.warn("No table definition handles {}:{} for current value", nodeType, propertyKey);
            return;
        }

        TableViewWindow window = new TableViewWindow(parentFrame, definition, currentValue, nodeName);
        String newValue = window.showAndGetResult();

        if (newValue != null) {
            // Always update when user clicks Accept - they may want to change
            // formatting even if the parsed values are the same.
            boolean success = executor.replacePropertyValue(propertyKey, currentValue, newValue, propertyLineNumber);
            if (success) {
                logger.info("Updated {} property via table view", propertyKey);
            }
        }
    }
}
