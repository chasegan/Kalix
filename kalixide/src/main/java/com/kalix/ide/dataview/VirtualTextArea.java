package com.kalix.ide.dataview;

import com.kalix.ide.constants.AppShortcut;
import com.kalix.ide.io.CsvLineStylist;
import com.kalix.ide.managers.FontManager;
import com.kalix.ide.themes.SyntaxTheme;
import com.kalix.ide.themes.ThemePreferences;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * The ground-truth projection of a {@link DataViewSession}: a read-only,
 * virtual text view rendering the file's <em>physical lines</em> exactly as
 * they sit on disk — delimiters, quoting, scientific notation, everything.
 * The glyphs are never altered; the only interpretation is colour, which
 * declares the sniffed dialect's reading (delimiters, header row, the date
 * column, missing markers) via the shared {@link CsvLineStylist}, using the
 * same syntax-theme slots as the editors below the gate.
 *
 * <p>Virtual in the same way as the table: only visible lines are ever held or
 * drawn; an unloaded line paints as blank for a frame while its block is
 * fetched in the background, and the arrival repaints. The component's
 * preferred height grows live while the file is being indexed, extending the
 * scrollbar as the count streams in. A 1GB file scrolls like a 10KB one.
 *
 * <p>Interaction is deliberately viewer-shaped for V1: whole-line selection by
 * click-drag and copy (the platform copy shortcut). Copy fetches the selected
 * range on a background thread — never the EDT — and is capped at
 * {@value #MAX_COPY_LINES} lines.
 */
public final class VirtualTextArea extends JComponent implements Scrollable {

    static final int MAX_COPY_LINES = 100_000;
    private static final int H_PAD = 8;
    private static final int MIN_WIDTH = 400;

    /** Non-final: replaced with a fresh session after the file is rebuilt from disk. */
    private DataViewSession session;

    /** Selected line range; -1 anchor = no selection. */
    private long selectionAnchor = -1;
    private long selectionCaret = -1;

    private int maxLineWidthPx = 0;
    private boolean widthRevalidateQueued = false;

    private Color background;
    private Color foreground;
    private Color selectionBackground;
    private Color selectionForeground;

    // Dialect-aware colouring: same six syntax-theme slots as the RSTA
    // editors, so above- and below-gate text views match. Values keep the
    // plain foreground — only the structure is coloured.
    private CsvLineStylist stylist;
    private boolean headerOnLineZero;
    private Color delimiterColor;
    private Color headerColor;
    private Color dateColor;
    private Color missingColor;

    public VirtualTextArea(DataViewSession session) {
        this.session = session;
        adoptDialect(session);
        setFont(FontManager.getMonospaceFont(13));
        setOpaque(true);
        setFocusable(true);
        resolveColors();

        registerSessionListener();

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                selectionAnchor = lineAt(e.getY());
                selectionCaret = selectionAnchor;
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                selectionCaret = lineAt(e.getY());
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);

        getInputMap(WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_C, AppShortcut.menuMask()), "copy-lines");
        getActionMap().put("copy-lines", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copySelection();
            }
        });
    }

    private void registerSessionListener() {
        session.addListener(new DataViewSession.Listener() {
            @Override
            public void onProgress(long rows, long lines, long indexedBytes, long totalBytes, boolean complete) {
                revalidate(); // preferred height grew
                repaint();
            }

            @Override
            public void onLineBlockLoaded(long firstLine, int count) {
                repaint();
            }
        });
    }

    /**
     * Swaps in a fresh session after the file was rebuilt from disk. EDT only;
     * the caller closes the old session (which drops its listeners) afterwards.
     */
    public void replaceSession(DataViewSession fresh) {
        this.session = fresh;
        adoptDialect(fresh);
        registerSessionListener();
        revalidate();
        repaint();
    }

    private void adoptDialect(DataViewSession target) {
        CsvDialect dialect = target.dialect();
        stylist = new CsvLineStylist(dialect.delimiter(), dialect.quote());
        headerOnLineZero = target.headerRowInData();
    }

    /** Scrolls the given line into view (with a little context) and selects it. EDT only. */
    public void showLine(long line) {
        long count = session.lineCount();
        if (count == 0) {
            return;
        }
        long target = Math.max(0, Math.min(line, count - 1));
        selectLines(target, target);
        int lineHeight = lineHeight();
        // Clamped like getPreferredSize: past the int-pixel ceiling (~100M+ lines)
        // this lands at the scrollable end rather than wrapping negative.
        long y = Math.max(0, (target - 2) * (long) lineHeight);
        scrollRectToVisible(new Rectangle(
            0, (int) Math.min(y, Integer.MAX_VALUE - 4096L), 1, lineHeight * 5));
        repaint();
    }

    /** Re-resolves theme colours; runs on every LaF switch via updateComponentTreeUI. */
    @Override
    public void updateUI() {
        super.updateUI();
        resolveColors();
        repaint();
    }

    private void resolveColors() {
        background = orElse(UIManager.getColor("TextArea.background"), Color.WHITE);
        foreground = orElse(UIManager.getColor("TextArea.foreground"), Color.BLACK);
        selectionBackground = orElse(UIManager.getColor("TextArea.selectionBackground"), new Color(51, 153, 255));
        selectionForeground = orElse(UIManager.getColor("TextArea.selectionForeground"), Color.WHITE);

        SyntaxTheme.Theme syntax;
        try {
            syntax = ThemePreferences.effectiveSyntaxTheme();
        } catch (Exception e) {
            syntax = SyntaxTheme.Theme.LIGHT;
        }
        delimiterColor = syntax.getOperatorColor();
        headerColor = syntax.getReservedWordColor();
        dateColor = syntax.getStringColor();
        missingColor = syntax.getCommentColor();
    }

    private static Color orElse(Color color, Color fallback) {
        return color != null ? color : fallback;
    }

    // --- Geometry ---

    private int lineHeight() {
        return getFontMetrics(getFont()).getHeight();
    }

    long lineAt(int y) {
        long count = session.lineCount();
        if (count == 0) {
            return -1;
        }
        return Math.max(0, Math.min(y / lineHeight(), count - 1));
    }

    @Override
    public Dimension getPreferredSize() {
        long height = session.lineCount() * lineHeight();
        return new Dimension(
            Math.max(MIN_WIDTH, maxLineWidthPx + 2 * H_PAD),
            (int) Math.min(height, Integer.MAX_VALUE));
    }

    // --- Painting ---

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(background);
        Rectangle clip = g.getClipBounds();
        g.fillRect(clip.x, clip.y, clip.width, clip.height);

        FontMetrics fm = getFontMetrics(getFont());
        int lineHeight = fm.getHeight();
        long first = Math.max(0, clip.y / lineHeight);
        long last = Math.min(session.lineCount() - 1, (long) (clip.y + clip.height) / lineHeight + 1);
        long selStart = selectionStart();
        long selEnd = selectionEndExclusive();

        g.setFont(getFont());
        for (long line = first; line <= last; line++) {
            int y = (int) (line * lineHeight);
            boolean selected = line >= selStart && line < selEnd;
            if (selected) {
                g.setColor(selectionBackground);
                g.fillRect(0, y, getWidth(), lineHeight);
            }
            String text = session.lineIfLoaded(line);
            if (text == null) {
                session.requestLine(line); // blank for a frame; block arrival repaints
                continue;
            }
            if (selected) {
                g.setColor(selectionForeground);
                g.drawString(text, H_PAD, y + fm.getAscent());
            } else {
                paintStyledLine(g, fm, text, line, y);
            }
            trackLineWidth(fm.stringWidth(text));
        }
    }

    /** Draws one line span-by-span in the dialect's colours (selection paints flat). */
    private void paintStyledLine(Graphics g, FontMetrics fm, String text, long line, int y) {
        int x = H_PAD;
        int baseline = y + fm.getAscent();
        for (CsvLineStylist.Span span : stylist.style(text, headerOnLineZero && line == 0)) {
            String part = text.substring(span.start(), span.endExclusive());
            g.setColor(colorFor(span.role()));
            g.drawString(part, x, baseline);
            x += fm.stringWidth(part);
        }
    }

    private Color colorFor(CsvLineStylist.Role role) {
        return switch (role) {
            case DELIMITER -> delimiterColor;
            case HEADER, MARKER -> headerColor;
            case DATE_AXIS -> dateColor;
            case MISSING -> missingColor;
            case VALUE -> foreground; // ground truth stays plain; only structure is coloured
        };
    }

    /** Widens the preferred size as longer lines are seen (coalesced revalidate). */
    private void trackLineWidth(int widthPx) {
        if (widthPx > maxLineWidthPx) {
            maxLineWidthPx = widthPx;
            if (!widthRevalidateQueued) {
                widthRevalidateQueued = true;
                SwingUtilities.invokeLater(() -> {
                    widthRevalidateQueued = false;
                    revalidate();
                });
            }
        }
    }

    // --- Selection & copy ---

    long selectionStart() {
        return selectionAnchor < 0 ? -1 : Math.min(selectionAnchor, selectionCaret);
    }

    /** One past the last selected line; -1 when there is no selection. */
    long selectionEndExclusive() {
        return selectionAnchor < 0 ? -1 : Math.max(selectionAnchor, selectionCaret) + 1;
    }

    /** Package-private, for tests and future programmatic selection. */
    void selectLines(long fromInclusive, long toInclusive) {
        selectionAnchor = fromInclusive;
        selectionCaret = toInclusive;
        repaint();
    }

    /**
     * The selected lines joined with {@code \n}. Blocking I/O — background
     * threads only; {@code null} when nothing is selected or the selection
     * exceeds {@link #MAX_COPY_LINES}.
     */
    String selectedTextBlocking() {
        long start = selectionStart();
        long end = selectionEndExclusive();
        if (start < 0 || end - start > MAX_COPY_LINES) {
            return null;
        }
        List<String> lines = session.linesBlocking(start, end);
        return String.join("\n", lines);
    }

    private void copySelection() {
        long start = selectionStart();
        long end = selectionEndExclusive();
        if (start < 0) {
            return;
        }
        if (end - start > MAX_COPY_LINES) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        Thread copier = new Thread(() -> {
            String text = selectedTextBlocking();
            if (text != null) {
                SwingUtilities.invokeLater(() ->
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(text), null));
            }
        }, "kalix-dataview-copy");
        copier.setDaemon(true);
        copier.start();
    }

    // --- Scrollable ---

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return lineHeight();
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL
            ? Math.max(lineHeight(), visibleRect.height - lineHeight())
            : visibleRect.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return false; // long lines scroll horizontally
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
