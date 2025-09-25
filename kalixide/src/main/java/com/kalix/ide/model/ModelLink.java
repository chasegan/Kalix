package com.kalix.ide.model;

/**
 * Represents a link between nodes in the hydrological model schematic.
 * Simplified data model for visualization purposes only.
 */
public final class ModelLink {
    private final String upstreamTerminus;
    private final String downstreamTerminus;
    private final boolean isPrimary;

    public ModelLink(String upstreamTerminus, String downstreamTerminus, boolean isPrimary) {
        this.upstreamTerminus = upstreamTerminus;
        this.downstreamTerminus = downstreamTerminus;
        this.isPrimary = isPrimary;
    }

    public String getUpstreamTerminus() {
        return upstreamTerminus;
    }

    public String getDownstreamTerminus() {
        return downstreamTerminus;
    }

    public boolean isPrimary() {
        return isPrimary;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        ModelLink modelLink = (ModelLink) obj;
        return upstreamTerminus.equals(modelLink.upstreamTerminus) &&
               downstreamTerminus.equals(modelLink.downstreamTerminus) &&
               isPrimary == modelLink.isPrimary;
    }

    @Override
    public int hashCode() {
        int result = upstreamTerminus.hashCode();
        result = 31 * result + downstreamTerminus.hashCode();
        result = 31 * result + Boolean.hashCode(isPrimary);
        return result;
    }

    @Override
    public String toString() {
        return String.format("ModelLink{%s -> %s}", upstreamTerminus, downstreamTerminus);
    }
}