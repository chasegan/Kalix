package com.kalix.ide.docking;

import com.kalix.ide.editor.EnhancedTextEditor;

import javax.swing.*;
import java.awt.*;

/**
 * A dockable version of the EnhancedTextEditor for use in the docking system.
 * 
 * This wrapper allows the existing EnhancedTextEditor to be used with the docking
 * system without requiring major modifications to the original component.
 */
public class DockableTextEditor extends DockablePanel {
    
    private EnhancedTextEditor textEditor;
    
    /**
     * Creates a new DockableTextEditor wrapping an existing EnhancedTextEditor.
     * @param textEditor The EnhancedTextEditor to make dockable
     */
    public DockableTextEditor(EnhancedTextEditor textEditor) {
        super(new BorderLayout());
        this.textEditor = textEditor;
        
        setupPanel();
    }
    
    /**
     * Creates a new DockableTextEditor with a new EnhancedTextEditor instance.
     */
    public DockableTextEditor() {
        this(new EnhancedTextEditor());
    }
    
    /**
     * Sets up the wrapped EnhancedTextEditor within this dockable panel.
     */
    private void setupPanel() {
        // Add the EnhancedTextEditor to this dockable panel
        // EnhancedTextEditor already includes its own scroll pane
        add(textEditor, BorderLayout.CENTER);
        
        // Forward focus to the wrapped panel when this panel gains focus
        addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                if (textEditor != null) {
                    textEditor.requestFocusInWindow();
                }
            }
        });
        
        // Set preferred size based on text editor
        if (textEditor.getPreferredSize() != null) {
            setPreferredSize(textEditor.getPreferredSize());
        } else {
            // Default size for text editor
            setPreferredSize(new Dimension(400, 300));
        }
    }
    
    /**
     * Gets the wrapped EnhancedTextEditor.
     * @return The EnhancedTextEditor instance
     */
    public EnhancedTextEditor getTextEditor() {
        return textEditor;
    }
    
    @Override
    protected void onDetach() {
        super.onDetach();
        // TextEditor-specific cleanup when detaching
        // The text editor maintains its state, so no special cleanup needed
    }
    
    @Override
    protected void onAttach(Container newParent) {
        super.onAttach(newParent);
        // TextEditor-specific setup when attaching to new parent
        // Request focus to ensure text editing works properly
        SwingUtilities.invokeLater(() -> {
            if (textEditor != null) {
                textEditor.requestFocusInWindow();
            }
        });
    }
    
    /**
     * Convenience method to forward common EnhancedTextEditor methods.
     * This allows the dockable panel to act as a proxy for the text editor.
     */
    
    public void setText(String text) {
        if (textEditor != null) {
            textEditor.setText(text);
        }
    }
    
    public String getText() {
        return textEditor != null ? textEditor.getText() : null;
    }
    
    public void undo() {
        if (textEditor != null) {
            textEditor.undo();
        }
    }
    
    public void redo() {
        if (textEditor != null) {
            textEditor.redo();
        }
    }
    
    public boolean canUndo() {
        return textEditor != null && textEditor.canUndo();
    }
    
    public boolean canRedo() {
        return textEditor != null && textEditor.canRedo();
    }
    
    public void cut() {
        if (textEditor != null) {
            textEditor.cut();
        }
    }
    
    public void copy() {
        if (textEditor != null) {
            textEditor.copy();
        }
    }
    
    public void paste() {
        if (textEditor != null) {
            textEditor.paste();
        }
    }
    
    public boolean isDirty() {
        return textEditor != null && textEditor.isDirty();
    }
    
    // Additional forwarding methods can be added as needed
    // for specific text editor functionality that needs to be accessible
    // from the docking system
}