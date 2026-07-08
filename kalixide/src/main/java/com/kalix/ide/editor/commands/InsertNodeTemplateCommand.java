package com.kalix.ide.editor.commands;

import com.kalix.ide.MapPanel;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import java.util.function.Supplier;

/**
 * Command to insert a node template of a given type at the current cursor's
 * node section.
 */
public class InsertNodeTemplateCommand implements EditorCommand {

    private static final Logger logger = LoggerFactory.getLogger(InsertNodeTemplateCommand.class);

    private final String nodeType;
    private final CommandMetadata metadata;
    private final Supplier<INIModelParser.ParsedModel> modelSupplier;
    private final Supplier<MapPanel> mapPanelSupplier;
    private final JFrame parentFrame;

    public InsertNodeTemplateCommand(String nodeType, Supplier<INIModelParser.ParsedModel> modelSupplier,
                                      Supplier<MapPanel> mapPanelSupplier, JFrame parentFrame) {
        this.nodeType = nodeType;
        this.modelSupplier = modelSupplier;
        this.mapPanelSupplier = mapPanelSupplier;
        this.parentFrame = parentFrame;
        this.metadata = new CommandMetadata.Builder()
            .id("insert_node_template_" + nodeType)
            .displayName(nodeType.toString())
            .description("Insert a default " + nodeType.toString() + " node template here")
            .category("Node template")
            .build();
    }

    @Override
    public CommandMetadata getMetadata() {
        return metadata;
    }

    @Override
    public boolean isApplicable(EditorContext context) {
        EditorContext.ContextType contextType = context.getType();
        return contextType == EditorContext.ContextType.NODE_HEADER
                || contextType == EditorContext.ContextType.NODE_SECTION
                || contextType == EditorContext.ContextType.PROPERTY
                || contextType == EditorContext.ContextType.UNKNOWN;
    }

    @Override
    public void execute(EditorContext context, CommandExecutor executor) {
        INIModelParser.ParsedModel parsedModel = modelSupplier.get();
        if (parsedModel == null) {
            logger.error("Failed to parse model");
            JOptionPane.showMessageDialog(
                parentFrame,
                "Failed to parse model",
                "Error",
                JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        double worldX = 0;
        double worldY = 0;
        MapPanel mapPanel = mapPanelSupplier != null ? mapPanelSupplier.get() : null;
        if (mapPanel != null) {
            java.awt.geom.Point2D.Double center = mapPanel.getCenterWorldPoint();
            worldX = center.x;
            worldY = center.y;
        }

        executor.insertNodeTemplateNearCursor(nodeType, worldX, worldY, parsedModel);
    }
}
