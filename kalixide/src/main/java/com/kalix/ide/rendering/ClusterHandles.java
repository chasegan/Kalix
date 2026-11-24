package com.kalix.ide.rendering;

import com.kalix.ide.model.ClusterCollapse;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.themes.NodeTheme;

import java.awt.FontMetrics;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * What stands for a cluster on the map, as the map draws it, in screen pixels: a collapsed
 * cluster's icon, and an expanded cluster's proxy and box name (clusters.md rule 11). The
 * map's hit-tests ask this, so a click finds what {@link MapRenderer} drew, from the same
 * {@link ClusterIcon} and {@link ClusterBox} geometry. Pure, so tested without a display.
 */
public final class ClusterHandles {

    /** The shapes drawn for one cluster. */
    public record Handle(String clusterName, List<Rectangle2D> shapes) {
        boolean contains(Point2D point) {
            return shapes.stream().anyMatch(shape -> shape.contains(point));
        }
    }

    private ClusterHandles() {
    }

    /** The icons of the collapsed clusters, both cards each, in drawing order. */
    public static List<Handle> icons(HydrologicalModel model, ClusterCollapse collapse,
                                     double zoomLevel, double panX, double panY) {
        List<Handle> handles = new ArrayList<>();
        for (MapLayers.Icon icon : MapLayers.icons(model, collapse)) {
            double x = icon.x() * zoomLevel + panX;
            double y = icon.y() * zoomLevel + panY;
            handles.add(new Handle(icon.clusterName(), List.of(ClusterIcon.face(x, y), ClusterIcon.back(x, y))));
        }
        return handles;
    }

    /**
     * The proxy, if boxes show one ({@link ClusterBox#showsProxy}), and the box name of each
     * expanded cluster, in drawing order. None with boxes hidden, or in a label mode that
     * draws no labels, which have neither (clusters.md rules 6, 8).
     */
    public static List<Handle> boxNames(HydrologicalModel model, ClusterCollapse collapse,
                                        FontMetrics fm, NodeTheme.TextStyle textStyle,
                                        NodeLabelMode labelMode, boolean showBoxes,
                                        double zoomLevel, double panX, double panY) {
        if (!showBoxes || !labelMode.drawsLabels()) {
            return List.of();
        }
        ClusterBox.TextMetrics metrics = MapRenderer.textMetrics(fm, textStyle);
        List<Handle> handles = new ArrayList<>();
        for (MapLayers.Layer layer : MapLayers.of(model, collapse)) {
            if (layer.clusterName() == null) {
                continue;
            }
            Rectangle2D box = MapRenderer.clusterBox(layer, fm, metrics, labelMode, zoomLevel, panX, panY);
            List<Rectangle2D> shapes = new ArrayList<>();
            if (ClusterBox.showsProxy()) {
                Rectangle2D proxy = ClusterBox.proxyFace(box.getX(), box.getY(), metrics);
                shapes.add(proxy);
                shapes.add(ClusterIcon.proxyBack(proxy.getX(), proxy.getY()));
            }
            shapes.add(ClusterBox.nameStrip(box.getX(), box.getY(),
                    fm.stringWidth(layer.clusterName()), metrics));
            handles.add(new Handle(layer.clusterName(), shapes));
        }
        return handles;
    }

    /** The cluster whose handle is drawn on top at {@code point}, or {@code null}. */
    public static String at(List<Handle> handles, Point2D point) {
        for (Handle handle : handles.reversed()) {
            if (handle.contains(point)) {
                return handle.clusterName();
            }
        }
        return null;
    }
}
