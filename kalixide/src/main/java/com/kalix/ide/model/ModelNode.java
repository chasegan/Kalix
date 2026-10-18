package com.kalix.ide.model;

import java.util.Objects;

/**
 * Represents a node in the hydrological model schematic.
 * Simplified data model for visualization purposes only.
 *
 * <p>{@code name} is the node's identity; {@code aka}, when present, is the
 * readable alternative name from an {@code #@aka} annotation (ADR-0010 §6.1). It is
 * a label in the sense of ADR-0003, never a key: collections stay keyed by name,
 * and {@code rendering.NodeLabelMode} is the one place that decides what is drawn.</p>
 */
public final class ModelNode {
    private final String name;
    private final String type;
    private final double x;
    private final double y;
    private final String aka;

    public ModelNode(String name, String type, double x, double y, String aka) {
        this.name = name;
        this.type = type;
        this.x = x;
        this.y = y;
        this.aka = aka;
    }

    public String getName() {
        return name;
    }

    /** The {@code #@aka} annotation's value, or {@code null} if the node has none. */
    public String getAka() {
        return aka;
    }

    public String getType() {
        return type;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ModelNode modelNode = (ModelNode) obj;
        return Double.compare(modelNode.x, x) == 0 &&
               Double.compare(modelNode.y, y) == 0 &&
               name.equals(modelNode.name) &&
               type.equals(modelNode.type) &&
               Objects.equals(aka, modelNode.aka);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + Double.hashCode(x);
        result = 31 * result + Double.hashCode(y);
        result = 31 * result + Objects.hashCode(aka);
        return result;
    }

    @Override
    public String toString() {
        return String.format("ModelNode{name='%s', type='%s', x=%.1f, y=%.1f, aka=%s}",
                name, type, x, y, aka == null ? "null" : "'" + aka + "'");
    }
}