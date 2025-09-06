package com.kalix.gui;

import io.github.andrewauclair.moderndocking.Dockable;
import javax.swing.*;
import java.awt.*;

public class SimpleDockable extends JPanel implements Dockable {
    private final JComponent component;
    private final String id;
    private final String title;
    
    public SimpleDockable(String id, String title, JComponent component) {
        this.id = id;
        this.title = title;
        this.component = component;
        
        // Add the component to this panel
        setLayout(new BorderLayout());
        add(component, BorderLayout.CENTER);
    }
    
    public String getTabText() {
        return title;
    }
    
    public String getPersistentID() {
        return id;
    }
    
    public boolean isCloseable() {
        return true;
    }
    
    public boolean isFloatable() {
        return true;
    }
    
    public boolean isLimitedToRoot() {
        return false;
    }
}