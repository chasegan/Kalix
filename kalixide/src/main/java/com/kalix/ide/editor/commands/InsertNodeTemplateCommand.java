package com.kalix.ide.editor.commands;

import com.kalix.ide.MapPanel;

import java.awt.geom.Point2D;
import java.util.function.Supplier;

/**
 * Command to insert a node template of a given type at the current cursor's
 * node section.
 */
public class InsertNodeTemplateCommand implements EditorCommand {

    private final String templateId;
    private final CommandMetadata metadata;
    private final Supplier<MapPanel> mapPanelSupplier;

    public InsertNodeTemplateCommand(NodeTemplateCatalog.NodeTemplate template, Supplier<MapPanel> mapPanelSupplier) {
        this.templateId = template.id();
        this.mapPanelSupplier = mapPanelSupplier;
        this.metadata = new CommandMetadata.Builder()
            .id("insert_node_template_" + template.id())
            .displayName(template.label())
            .description("Insert a default " + template.label() + " node here")
            .category("Insert node")
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
        // No click location from a text-editor invocation: place the node at the
        // centre of the map view. The section's position comes from the caret.
        double worldX = 0;
        double worldY = 0;
        MapPanel mapPanel = mapPanelSupplier != null ? mapPanelSupplier.get() : null;
        if (mapPanel != null) {
            Point2D.Double center = mapPanel.getCenterWorldPoint();
            worldX = center.x;
            worldY = center.y;
        }

        executor.insertNodeTemplateNearCursor(templateId, worldX, worldY);
    }
}
