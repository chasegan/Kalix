package com.kalix.ide.docking;

import com.kalix.ide.editor.EnhancedTextEditor;
import java.awt.BorderLayout;

/**
 * A dockable wrapper for the EnhancedTextEditor component.
 * Extends DockablePanel to provide docking functionality while
 * maintaining all the original text editor features.
 */
public class DockableTextEditor extends DockablePanel {

    private EnhancedTextEditor textEditor;

    public DockableTextEditor() {
        super(new BorderLayout());
        initializeTextEditor();
    }

    /**
     * Initializes the wrapped EnhancedTextEditor and adds it to this dockable container.
     */
    private void initializeTextEditor() {
        textEditor = new EnhancedTextEditor();
        add(textEditor, BorderLayout.CENTER);

        // The editor and its text area fill this panel, so forward their hover
        // events up so docking-mode highlighting still works.
        forwardHoverEvents(textEditor);
        if (textEditor.getTextArea() != null) {
            forwardHoverEvents(textEditor.getTextArea());
        }

        // Register services with the docking context
        DockingContext context = DockingContext.getInstance();
        context.registerService("textEditor", textEditor);
        context.registerService(EnhancedTextEditor.class, textEditor);
    }

    /**
     * Returns the wrapped EnhancedTextEditor instance.
     */
    public EnhancedTextEditor getTextEditor() {
        return textEditor;
    }


    public void setText(String text) {
        if (textEditor != null) {
            textEditor.setText(text);
        }
    }

    @Override
    public String toString() {
        return "Text Editor";
    }
}