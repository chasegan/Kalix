package com.kalix.ide.editor.search;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Segment;
import java.util.function.BooleanSupplier;

/**
 * A read-only {@link CharSequence} view over a Swing {@link Document}, for scanning a
 * document off the EDT without copying it.
 *
 * <h2>Why not just call {@code getText()}</h2>
 * The obvious approach — snapshot the document into a String and scan that — costs a
 * full copy up front, in {@code char}s, so a 200 MB file becomes another 400 MB of heap
 * before the search even starts. This class instead pulls a window of text at a time,
 * so scanning costs one buffer regardless of document size.
 *
 * <h2>Thread safety</h2>
 * Each refill happens inside {@link Document#render(Runnable)}, which is one of the few
 * genuinely thread-safe entry points in Swing text: it takes the document's read lock,
 * so the window cannot be filled while a write is in progress. The text is copied out
 * of the {@link Segment} <em>inside</em> that lock, because a Segment may alias the
 * document's internal arrays and would otherwise be read without protection.
 *
 * <p>The lock is held per window, never for the whole scan, so a long search cannot
 * block typing in the editor — the writer only ever waits for one buffer's worth.</p>
 *
 * <h2>Cancellation</h2>
 * {@link #charAt} polls the supplied predicate and throws {@link SearchCancelledException}
 * once it reports cancellation. This is the only way to stop a running
 * {@link java.util.regex.Matcher}, which is otherwise uninterruptible. The poll is
 * amortised over {@link #CANCEL_POLL_INTERVAL} accesses so it costs nothing measurable
 * in the inner loop while still bounding how long a cancelled scan runs on.
 *
 * <p>Not thread safe: one instance belongs to one scanning thread.</p>
 */
public final class DocumentCharSequence implements CharSequence {

    /** Characters fetched per refill. Large enough to amortise the read lock. */
    private static final int WINDOW = 1 << 16;

    /**
     * How much of the window sits <em>behind</em> the requested index. Regex matching
     * backtracks, so a window that started at the request would thrash on every
     * backward step.
     */
    private static final int LOOKBEHIND = WINDOW / 4;

    /** Poll cancellation every this many accesses (a power of two, so the test is a mask). */
    private static final int CANCEL_POLL_INTERVAL = 4096;

    private final Document document;
    private final int offset;
    private final int length;
    private final BooleanSupplier cancelled;

    private final Segment segment = new Segment();
    private final char[] window = new char[WINDOW];

    /** Sequence coordinates currently held in {@link #window}; empty when start == end. */
    private int windowStart;
    private int windowEnd;

    private int accessCount;

    /** A view over the whole document. */
    public DocumentCharSequence(Document document, BooleanSupplier cancelled) {
        this(document, 0, document.getLength(), cancelled);
    }

    private DocumentCharSequence(Document document, int offset, int length, BooleanSupplier cancelled) {
        this.document = document;
        this.offset = offset;
        this.length = length;
        this.cancelled = cancelled;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public char charAt(int index) {
        if ((++accessCount & (CANCEL_POLL_INTERVAL - 1)) == 0 && cancelled.getAsBoolean()) {
            throw new SearchCancelledException();
        }
        if (index < windowStart || index >= windowEnd) {
            fill(index);
        }
        return window[index - windowStart];
    }

    /**
     * Loads the window containing {@code index}.
     *
     * <p>A {@link BadLocationException} here means the document shrank under us — the
     * scan is racing an edit and its result is already worthless, so it unwinds as a
     * cancellation rather than an error.</p>
     */
    private void fill(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("index " + index + " outside [0, " + length + ")");
        }
        if (cancelled.getAsBoolean()) {
            throw new SearchCancelledException();
        }

        int start = Math.max(0, index - LOOKBEHIND);
        int end = Math.min(length, start + WINDOW);
        int count = end - start;

        boolean[] stale = {false};
        document.render(() -> {
            try {
                document.getText(offset + start, count, segment);
                System.arraycopy(segment.array, segment.offset, window, 0, count);
            } catch (BadLocationException e) {
                stale[0] = true;
            }
        });
        if (stale[0]) {
            throw new SearchCancelledException();
        }

        windowStart = start;
        windowEnd = end;
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        if (start < 0 || end > length || start > end) {
            throw new IndexOutOfBoundsException("[" + start + ", " + end + ") outside [0, " + length + ")");
        }
        // Materialised: subSequence is called by Matcher.group(), where the caller wants
        // the matched text itself. Matches are small relative to the document.
        StringBuilder sb = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            sb.append(charAt(i));
        }
        return sb.toString();
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Materialises the entire document.</strong> Present only to honour the
     * {@link CharSequence} contract; calling it defeats this class's whole purpose and
     * will exhaust the heap on the large files it exists to handle. Nothing on the
     * scanning path calls it.</p>
     */
    @Override
    public String toString() {
        return subSequence(0, length).toString();
    }
}
