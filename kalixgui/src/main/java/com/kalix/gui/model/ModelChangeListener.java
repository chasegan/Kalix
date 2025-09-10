package com.kalix.gui.model;

/**
 * Listener interface for hydrological model changes.
 */
@FunctionalInterface
public interface ModelChangeListener {
    
    /**
     * Called when the model changes.
     * 
     * @param event The change event with details
     */
    void onModelChanged(ModelChangeEvent event);
}