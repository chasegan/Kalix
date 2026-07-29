package com.kalix.ide.editor.search;

import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs {@link MatchScanner} off the EDT, one scan at a time, cancelling the previous
 * scan whenever a new one is requested.
 *
 * <h2>The guarantee</h2>
 * At most one scan is ever in flight. Submitting a new one cancels its predecessor
 * immediately, so find-as-you-type over a very large file cannot pile up work: each
 * keystroke abandons the last scan wherever it had reached. Cancellation is prompt
 * rather than merely eventual, because the abandoned scan is reading through a
 * {@link DocumentCharSequence} that polls the flag and unwinds.
 *
 * <h2>Threading contract</h2>
 * {@link #scan} and {@link #cancel} must be called on the EDT, and every callback is
 * delivered on the EDT. The only work off the EDT is the scan itself, which touches the
 * document solely through {@code Document.render}. Callers therefore never need a lock:
 * everything they can observe is EDT-confined.
 *
 * <p>A superseded scan is silent — no callback fires for it. A caller that has shown a
 * "counting…" state should clear it when the <em>next</em> result arrives, not expect a
 * cancellation notice.</p>
 */
public final class AsyncMatchScanner {

    private final ExecutorService executor;

    /** Cancellation flag of the in-flight scan. EDT-confined. */
    private AtomicBoolean inFlight;

    public AsyncMatchScanner() {
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kalix-search-scanner");
            // Daemon: a scan of a huge file must never hold the JVM open at shutdown.
            thread.setDaemon(true);
            // Below normal: the EDT's responsiveness outranks the match counter, which
            // is exactly the trade this class exists to make.
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    /**
     * Cancels any scan in flight and starts a new one.
     *
     * @param document   the document to scan; read only under {@code Document.render}
     * @param query      what to look for
     * @param maxMatches cap passed to {@link MatchScanner#scan}
     * @param onComplete receives the result on the EDT; not called if superseded
     * @param onFailure  receives a scan failure (an invalid pattern, say) on the EDT
     */
    public void scan(Document document, SearchQuery query, int maxMatches,
                     Consumer<MatchScan> onComplete, Consumer<RuntimeException> onFailure) {
        compute(document,
            text -> MatchScanner.scan(text, query, maxMatches),
            onComplete, onFailure);
    }

    /**
     * Cancels any work in flight and runs {@code work} against the document off the EDT.
     *
     * <p>The generalisation of {@link #scan}: Replace All plans its edits by walking the
     * whole document too, and wants the same cancellable, non-copying, EDT-free
     * treatment. {@code work} is handed a {@link DocumentCharSequence}, so it inherits
     * both the windowed reads and the cancellation polling.</p>
     *
     * @param work computation over the document; must not touch Swing
     */
    public <T> void compute(Document document, Function<CharSequence, T> work,
                            Consumer<T> onComplete, Consumer<RuntimeException> onFailure) {
        cancel();

        AtomicBoolean cancelled = new AtomicBoolean();
        inFlight = cancelled;

        executor.execute(() -> {
            try {
                T result = work.apply(new DocumentCharSequence(document, cancelled::get));
                publish(cancelled, () -> onComplete.accept(result));
            } catch (SearchCancelledException e) {
                // Superseded. The successor owns the UI now; say nothing.
            } catch (RuntimeException e) {
                publish(cancelled, () -> onFailure.accept(e));
            }
        });
    }

    /**
     * Abandons the scan in flight, if any. Idempotent.
     *
     * <p>The flag is dropped as well as set, so a scan that finishes between the flag
     * being set and its thread noticing still finds itself cancelled and stays silent.</p>
     */
    public void cancel() {
        if (inFlight != null) {
            inFlight.set(true);
            inFlight = null;
        }
    }

    /**
     * Delivers a callback on the EDT unless the scan was cancelled — tested twice, since
     * cancellation can land after the scan finishes but before the callback runs.
     */
    private static void publish(AtomicBoolean cancelled, Runnable callback) {
        if (cancelled.get()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!cancelled.get()) {
                callback.run();
            }
        });
    }

    /**
     * Cancels any scan in flight and shuts the executor down. The owning editor calls
     * this from its own dispose, alongside the other executor-backed managers.
     */
    public void dispose() {
        cancel();
        executor.shutdownNow();
    }
}
