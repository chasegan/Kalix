package com.kalix.ide.linter.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.ToolTipSupplier;

import javax.swing.ToolTipManager;
import javax.swing.text.BadLocationException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The editor's hover tooltips — validation issues and property help — delivered
 * through Swing's own {@link ToolTipManager} rather than a window of our own.
 *
 * <p>The previous implementation showed its content in an ownerless
 * {@code JWindow}, a top-level window in its own right: it did not minimise with
 * the frame, did not follow it to another desktop, and hid only on mouse events
 * over the text area, so a keyboard action (close tab, minimise, switch desktop)
 * left it standing. {@code ToolTipManager} places a tip inside the frame's layered
 * pane when it fits and in a window <em>owned</em> by the frame when it does not,
 * and hides it on mouse exit, mouse press, focus loss, and whenever the frame
 * moves, hides or resizes. Nothing here can outlive the frame.</p>
 *
 * <p>Precedence is explicit: when the hovered line carries validation issues,
 * their tip wins; otherwise the property-help source is asked. Both sources
 * receive the resolved 1-based line and document offset under the pointer, so
 * they contain no view geometry and can be unit-tested headless.</p>
 *
 * <p>Note that {@code RSyntaxTextArea} defaults to its "focusable tips", a tip
 * window of its own that calls {@code requestFocus()} on the text area every
 * time it closes — hovering the editor would steal keyboard focus from wherever
 * it was. They are switched off here.</p>
 */
public final class HoverTipSupplier implements ToolTipSupplier {

    /** A tooltip source: returns HTML (starting with {@code <html>}) or {@code null}. */
    public interface Source {
        /**
         * @param line   1-based line under the pointer
         * @param offset document offset under the pointer
         * @return the tip's HTML, or {@code null} for no tip at this position
         */
        String tipAt(int line, int offset);
    }

    /**
     * How long a tip stays up while the pointer rests in the editor. The
     * application-wide dismiss delay (4 s, right for toolbar hints) is too short
     * to read a stack of lint messages, so it is raised while the pointer is
     * inside the text area and restored on exit — the standard idiom for a
     * per-component dismiss delay, which {@link ToolTipManager} has no API for.
     */
    static final int EDITOR_DISMISS_DELAY_MS = 15_000;

    private final RSyntaxTextArea textArea;
    private final MouseAdapter dismissDelayListener;
    private volatile Source issueSource;
    private volatile Source helpSource;
    private int savedDismissDelay = -1;
    private boolean installed;

    private HoverTipSupplier(RSyntaxTextArea textArea) {
        this.textArea = textArea;
        this.dismissDelayListener = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                ToolTipManager manager = ToolTipManager.sharedInstance();
                savedDismissDelay = manager.getDismissDelay();
                manager.setDismissDelay(EDITOR_DISMISS_DELAY_MS);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                restoreDismissDelay();
            }
        };
    }

    /**
     * Installs hover tips on {@code textArea}: registers it with the shared
     * {@link ToolTipManager}, routes tip text through this supplier, and turns
     * off the library's focusable tips. Sources are attached afterwards with
     * {@link #setIssueSource} and {@link #setHelpSource}.
     */
    public static HoverTipSupplier install(RSyntaxTextArea textArea) {
        HoverTipSupplier supplier = new HoverTipSupplier(textArea);
        textArea.setUseFocusableTips(false);
        textArea.setToolTipSupplier(supplier);
        textArea.addMouseListener(supplier.dismissDelayListener);
        ToolTipManager.sharedInstance().registerComponent(textArea);
        supplier.installed = true;
        return supplier;
    }

    /** The validation-issue source, consulted first; {@code null} to remove. */
    public void setIssueSource(Source source) {
        this.issueSource = source;
    }

    /** The property-help source, consulted when the line has no issue tip; {@code null} to remove. */
    public void setHelpSource(Source source) {
        this.helpSource = source;
    }

    @Override
    public String getToolTipText(RTextArea area, MouseEvent e) {
        if (issueSource == null && helpSource == null) {
            return null;
        }

        int offset = textArea.viewToModel2D(e.getPoint());
        if (offset < 0) {
            return null;
        }
        int line;
        try {
            line = textArea.getLineOfOffset(offset) + 1;
        } catch (BadLocationException ex) {
            return null;
        }
        return tipFor(line, offset);
    }

    /** The tip for a resolved position: issues first, then help. Package-private for tests. */
    String tipFor(int line, int offset) {
        Source issues = issueSource;
        if (issues != null) {
            String tip = issues.tipAt(line, offset);
            if (tip != null) {
                return tip;
            }
        }
        Source help = helpSource;
        return help != null ? help.tipAt(line, offset) : null;
    }

    /**
     * Detaches everything installed by {@link #install}: the supplier, the mouse
     * listener, and the {@link ToolTipManager} registration. Idempotent.
     */
    public void uninstall() {
        if (!installed) {
            return;
        }
        installed = false;
        issueSource = null;
        helpSource = null;
        restoreDismissDelay();
        textArea.removeMouseListener(dismissDelayListener);
        if (textArea.getToolTipSupplier() == this) {
            textArea.setToolTipSupplier(null);
        }
        ToolTipManager.sharedInstance().unregisterComponent(textArea);
    }

    private void restoreDismissDelay() {
        if (savedDismissDelay >= 0) {
            ToolTipManager.sharedInstance().setDismissDelay(savedDismissDelay);
            savedDismissDelay = -1;
        }
    }

    /**
     * Escapes text for inclusion in tip HTML. Lint messages routinely contain
     * {@code <=} and {@code >=}, which Swing's HTML renderer would otherwise eat.
     */
    public static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
