package com.kalix.ide.models.optimisation;

/**
 * Enum representing the status of an optimisation run.
 * Provides display-friendly names for UI presentation.
 */
public enum OptimisationStatus {
    CONFIGURING("Configuring"),
    STARTING("Starting"),
    LOADING("Loading Model"),
    RUNNING("Optimising"),
    DONE("Complete"),
    ERROR("Failed"),
    STOPPED("Stopped");

    private final String displayName;

    OptimisationStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /**
     * Gets the appropriate color for this status.
     * Used for UI rendering in trees and labels.
     *
     * @return A color appropriate for this status
     */
    public java.awt.Color getStatusColor() {
        switch (this) {
            case DONE:
                return new java.awt.Color(0, 120, 0);     // Dark green
            case RUNNING:
            case LOADING:
                return new java.awt.Color(0, 0, 200);     // Blue
            case ERROR:
                return new java.awt.Color(200, 0, 0);     // Red
            case STARTING:
            case CONFIGURING:
                return new java.awt.Color(150, 150, 0);   // Dark yellow
            case STOPPED:
                return new java.awt.Color(128, 128, 128); // Gray
            default:
                return java.awt.Color.BLACK;
        }
    }
}