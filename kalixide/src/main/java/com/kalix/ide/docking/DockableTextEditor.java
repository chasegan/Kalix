package com.kalix.ide.docking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kalix.ide.editor.EnhancedTextEditor;
import java.awt.BorderLayout;

/**
 * A dockable wrapper for the EnhancedTextEditor component.
 * Extends DockablePanel to provide docking functionality while
 * maintaining all the original text editor features.
 */
public class DockableTextEditor extends DockablePanel {
    private static final Logger logger = LoggerFactory.getLogger(DockableTextEditor.class);

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

        // Forward key events from child to parent for docking functionality
        setupKeyEventForwarding();

        // Register services with the docking context
        DockingContext context = DockingContext.getInstance();
        context.registerService("textEditor", textEditor);
        context.registerService(EnhancedTextEditor.class, textEditor);
    }

    /**
     * Sets up key and mouse event forwarding from the child EnhancedTextEditor to this DockablePanel.
     * This ensures F9 events and mouse hover events reach the docking system even when the child has focus.
     */
    private void setupKeyEventForwarding() {
        // Set up mouse event forwarding for hover detection
        textEditor.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                logger.info("Mouse entered TextEditor, forwarding to DockablePanel");
                // Forward to parent's mouse listeners
                for (java.awt.event.MouseListener listener : getMouseListeners()) {
                    listener.mouseEntered(e);
                }
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                logger.info("Mouse exited TextEditor, forwarding to DockablePanel");
                // Forward to parent's mouse listeners
                for (java.awt.event.MouseListener listener : getMouseListeners()) {
                    listener.mouseExited(e);
                }
            }
        });
        textEditor.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                // Forward F9 events to the parent DockablePanel
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_F9) {
                    // Dispatch the event to this DockablePanel's key listeners
                    for (java.awt.event.KeyListener listener : getKeyListeners()) {
                        listener.keyPressed(e);
                    }
                }
            }

            @Override
            public void keyReleased(java.awt.event.KeyEvent e) {
                // Forward F9 events to the parent DockablePanel
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_F9) {
                    // Dispatch the event to this DockablePanel's key listeners
                    for (java.awt.event.KeyListener listener : getKeyListeners()) {
                        listener.keyReleased(e);
                    }
                }
            }
        });

        // Ensure the EnhancedTextEditor can receive focus for key events
        textEditor.setFocusable(true);
    }

    /**
     * Returns the wrapped EnhancedTextEditor instance.
     */
    public EnhancedTextEditor getTextEditor() {
        return textEditor;
    }

    /**
     * Delegates method calls to the wrapped EnhancedTextEditor for convenience.
     */
    public String getText() {
        return textEditor != null ? textEditor.getText() : "";
    }

    public void setText(String text) {
        if (textEditor != null) {
            textEditor.setText(text);
        }
    }

    public boolean isDirty() {
        return textEditor != null && textEditor.isDirty();
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

    public boolean canUndo() {
        return textEditor != null && textEditor.canUndo();
    }

    public boolean canRedo() {
        return textEditor != null && textEditor.canRedo();
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

    @Override
    public String toString() {
        return "Text Editor";
    }
}