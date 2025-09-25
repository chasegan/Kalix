package com.kalix.ide.model;

/**
 * Represents a node in the hydrological model schematic.
 * Simplified data model for visualization purposes only.
 */
public final class ModelNode {
    private final String name;
    private final String type;
    private final double x;
    private final double y;

    public ModelNode(String name, String type, double x, double y) {
        this.name = name;
        this.type = type;
        this.x = x;
        this.y = y;
    }

    public String getName() {
        return name;
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
               type.equals(modelNode.type);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + Double.hashCode(x);
        result = 31 * result + Double.hashCode(y);
        return result;
    }

    @Override
    public String toString() {
        return String.format("ModelNode{name='%s', type='%s', x=%.1f, y=%.1f}", name, type, x, y);
    }
}