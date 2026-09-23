package com.kalix.ide.interaction;

import com.kalix.ide.MapPanel;
import com.kalix.ide.constants.UIConstants;
import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelNode;
import com.kalix.ide.rendering.MapRenderer;

import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.dnd.DragSource;
import java.util.Objects;

/**
 * Manages dragging a new link out of a node's ring on the schematic map (issue #23):
 * the press that starts the drag, the preview that follows the mouse and snaps to a
 * droppable node, writing the {@code ds_N} line on release, and the ring affordance
 * shown on hover when nothing is being dragged.
 *
 * <p>A press within the ring just outside a node (not on the node itself, which selects
 * or moves it — see {@link #ringNodeAt}) starts a drag. The cursor shows whether the
 * drop under it would be accepted ({@link LinkRules#allows}); a release there writes the
 * link via {@link EnhancedTextEditor#addLink}, anywhere else just cancels.</p>
 */
public class LinkDragManager {
    private final MapPanel mapPanel;
    private final HydrologicalModel model;
    private final EnhancedTextEditor textEditor;
    private final MapRenderer mapRenderer;

    // Drag state: dragSource is non-null only while a drag is in progress.
    private String dragSource;
    private Point dragPoint;
    private String dragTarget;
    private boolean dragTargetAllowed;

    /** Node whose ring is under the idle mouse (no drag in progress), or null. */
    private String hoverNode;

    public LinkDragManager(MapPanel mapPanel, HydrologicalModel model,
                           EnhancedTextEditor textEditor, MapRenderer mapRenderer) {
        this.mapPanel = mapPanel;
        this.model = model;
        this.textEditor = textEditor;
        this.mapRenderer = mapRenderer;
    }

    /** True while a link is being dragged out. */
    public boolean isDragging() {
        return dragSource != null;
    }

    /** True while the idle-mouse ring affordance is shown on some node. */
    public boolean isHovering() {
        return hoverNode != null;
    }

    /**
     * The node nearest the given screen point, within its circle or the ring just
     * outside it ({@link UIConstants.Map#LINK_HANDLE_RING_PX}). Used both to decide
     * whether a press starts a drag and, when the point is not on any node, to show
     * the hover ring.
     *
     * @param screenPoint Screen coordinates (mouse position)
     * @return Node name if found, null if no node is that close
     */
    public String ringNodeAt(Point screenPoint) {
        double reach = UIConstants.Map.NODE_SIZE / 2.0 + UIConstants.Map.LINK_HANDLE_RING_PX;
        String nearest = null;
        double nearestDistance = reach;
        for (ModelNode node : model.getAllNodes()) {
            double distance = screenPoint.distance(mapPanel.toScreenX(node.getX()), mapPanel.toScreenY(node.getY()));
            if (distance <= nearestDistance) {
                nearest = node.getName();
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /**
     * Recomputes the hover ring for the idle mouse (none for a null point — e.g. Shift
     * or a rotation modifier held, or the mouse has left the map), returning whether it
     * changed. On a node itself there is no ring: pressing there selects or moves it.
     */
    public boolean updateHover(Point screenPoint) {
        String near = screenPoint != null ? ringNodeAt(screenPoint) : null;
        String hover = near != null && mapPanel.getNodeAtPoint(screenPoint) == null ? near : null;
        if (Objects.equals(hover, hoverNode)) {
            return false;
        }
        hoverNode = hover;
        return true;
    }

    /** Starts dragging a new link out of {@code sourceNode}. */
    public void startDrag(String sourceNode, Point screenPoint) {
        dragSource = sourceNode;
        hoverNode = null;
        mapPanel.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        updateDrag(screenPoint);
    }

    /** Follows the mouse: snaps to the node under it and shows by cursor whether it can be linked. */
    public void updateDrag(Point screenPoint) {
        dragPoint = new Point(screenPoint);
        String near = ringNodeAt(screenPoint);
        String target = dragSource.equals(near) ? null : near;
        if (!Objects.equals(target, dragTarget)) {
            dragTarget = target;
            // Only on a change of target: the loop check walks the network.
            dragTargetAllowed = target != null && LinkRules.allows(model.getAllLinks(), dragSource, target);
            mapPanel.setCursor(dragCursor());
        }
        mapPanel.repaint();
    }

    /** Crosshair over empty map; the platform's link drop / no-drop cursor over a node. */
    private Cursor dragCursor() {
        Cursor cursor = dragTarget == null ? null
            : dragTargetAllowed ? DragSource.DefaultLinkDrop : DragSource.DefaultLinkNoDrop;
        // DragSource leaves these null if the platform could not supply them.
        return cursor != null ? cursor : Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    }

    /** Writes the link if released on a node that can take it; otherwise just ends the drag. */
    public void finishDrag() {
        if (dragTarget != null && dragTargetAllowed) {
            ModelNode source = model.getNode(dragSource);
            textEditor.addLink(dragSource, source != null ? source.getType() : null, dragTarget);
        }
        cancel();
    }

    /** Ends any drag in progress without writing a link — a new press, a plain mouse
     * move (no button held, so a release was lost), or Esc all call this. */
    public void cancel() {
        dragSource = null;
        dragPoint = null;
        dragTarget = null;
        dragTargetAllowed = false;
        mapPanel.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        mapPanel.repaint();
    }

    /** Paints the link being dragged, or the ring affordance under the idle mouse. */
    public void paint(Graphics2D g2d) {
        if (dragSource != null) {
            ModelNode source = model.getNode(dragSource);
            if (source == null) {
                return; // source vanished mid-drag (text edited); release will clear
            }
            ModelNode target = dragTarget != null ? model.getNode(dragTarget) : null;
            boolean valid = target == null || dragTargetAllowed;
            double toX = target != null ? mapPanel.toScreenX(target.getX()) : dragPoint.x;
            double toY = target != null ? mapPanel.toScreenY(target.getY()) : dragPoint.y;
            mapRenderer.renderLinkDrag(g2d, mapPanel.toScreenX(source.getX()), mapPanel.toScreenY(source.getY()),
                toX, toY, valid);
            if (target != null) {
                mapRenderer.renderLinkHandle(g2d, toX, toY, valid);
            }
        } else if (hoverNode != null) {
            ModelNode node = model.getNode(hoverNode);
            if (node != null) {
                mapRenderer.renderLinkHandle(g2d, mapPanel.toScreenX(node.getX()), mapPanel.toScreenY(node.getY()), true);
            }
        }
    }
}
