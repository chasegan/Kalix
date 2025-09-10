package com.kalix.gui.model;

/**
 * Represents a link between nodes in the hydrological model schematic.
 * Simplified data model for visualization purposes only.
 */
public final class ModelLink {
    private final String upstreamTerminus;
    private final String downstreamTerminus;

    public ModelLink(String upstreamTerminus, String downstreamTerminus) {
        this.upstreamTerminus = upstreamTerminus;
        this.downstreamTerminus = downstreamTerminus;
    }

    public String getUpstreamTerminus() {
        return upstreamTerminus;
    }

    public String getDownstreamTerminus() {
        return downstreamTerminus;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ModelLink modelLink = (ModelLink) obj;
        return upstreamTerminus.equals(modelLink.upstreamTerminus) &&
               downstreamTerminus.equals(modelLink.downstreamTerminus);
    }

    @Override
    public int hashCode() {
        int result = upstreamTerminus.hashCode();
        result = 31 * result + downstreamTerminus.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return String.format("ModelLink{%s -> %s}", upstreamTerminus, downstreamTerminus);
    }
}