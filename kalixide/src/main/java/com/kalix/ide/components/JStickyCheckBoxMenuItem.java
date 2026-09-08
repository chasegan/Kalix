package com.kalix.ide.components;

/**
 * A checkbox menu item that leaves its popup open when activated, so a user can
 * pick items in one visit. Swing dismisses the menu on activation, which
 * is right for one-shot commands and wrong for a multi-select value list.
 */
public class JStickyCheckBoxMenuItem extends javax.swing.JCheckBoxMenuItem {

    /** The menu path captured while armed, reinstated once activation has cleared it. */
    private javax.swing.MenuElement[] selectionPath;

    public JStickyCheckBoxMenuItem(String s) {
        super(s);
        // Arming precedes the UI clearing the selected path, so this is the last
        // chance to record where in the menu hierarchy we were.
        getModel().addChangeListener(_ -> {
            if (getModel().isArmed() && isShowing()) {
                selectionPath = javax.swing.MenuSelectionManager.defaultManager().getSelectedPath();
            }
        });
    }

    @Override
    protected void processMouseEvent(java.awt.event.MouseEvent e) {
        // The look and feel's own mouseReleased handler dismisses the popup before
        // it fires the item. Swallow the release and do the work here instead, so
        // the dismissal never happens on a mouse click.
        if (e.getID() == java.awt.event.MouseEvent.MOUSE_RELEASED && contains(e.getPoint())) {
            // Zero press time: the no-arg doClick() sleeps 68 ms on the event thread for
            // visual feedback, which the mouse press has already provided.
            doClick(0);
            setArmed(true);   // stay highlighted; the cursor is still over us
            return;
        }
        super.processMouseEvent(e);
    }

    @Override
    public void doClick(int pressTime) {
        // Keyboard activation (Space/Enter) still goes through the UI, which clears the
        // selected path first - and clearing the path is what hides the popup.
        super.doClick(pressTime);
        if (selectionPath == null) {
            return;
        }
        // Reinstating the path alone is not enough: a popup dropped from a plain button
        // (rather than a JMenu) does not re-show when its path comes back, so we would be
        // left with a hidden popup that still owns the menu selection - and the popup's
        // mouse grabber would then swallow the next click anywhere in the window. Re-show
        // it explicitly (it remembers where it was), then restore the path so this item
        // stays armed. On the mouse path the popup never hid, so this is a no-op.
        if (getParent() instanceof javax.swing.JPopupMenu popup && !popup.isVisible()) {
            popup.setVisible(true);
        }
        javax.swing.MenuSelectionManager.defaultManager().setSelectedPath(selectionPath);
    }
}
