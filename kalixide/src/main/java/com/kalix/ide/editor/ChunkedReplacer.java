package com.kalix.ide.editor;

import com.kalix.ide.editor.search.Replacement;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.util.List;
import java.util.function.Consumer;

/**
 * Applies a Replace All plan to the document without freezing the UI.
 *
 * <h2>Modal, not frozen</h2>
 * A replace must stop the modeller editing text that is being rewritten underneath them
 * — but that is <em>modality</em>, and blocking the EDT is the wrong way to get it. A
 * frozen EDT buys the same protection at the cost of every useful thing: no repaint, no
 * progress, no cancel, and after a second or two the platform marks the application
 * unresponsive, which reads to a user as a crash at precisely the moment we need to say
 * "this is working". So the work is chunked across event-dispatch turns behind a modal
 * dialog: input is blocked, and the UI stays alive.
 *
 * <h2>Back to front</h2>
 * The plan's offsets describe the document as it was when the plan was built, so edits
 * are applied last-match-first: every remaining edit then sits <em>before</em> the text
 * that just changed, and its offsets are still valid. No remapping, no drift.
 *
 * <p>It is also the kinder order for a {@link javax.swing.text.GapContent} document,
 * which moves its gap to each edit site. Walking backwards moves the gap steadily in one
 * direction, so the total movement is proportional to the document length rather than to
 * the document length times the number of edits.</p>
 *
 * <h2>Undo</h2>
 * The whole run is bracketed by {@code beginAtomicEdit}/{@code endAtomicEdit}, so it is
 * a single undoable step — including a run that was cancelled part way, which is what
 * makes "undo to revert" an honest thing to tell the user.
 */
final class ChunkedReplacer {

    /**
     * How long one chunk may hold the dispatch thread. Comfortably inside a frame, so
     * the progress bar animates and Cancel is noticed promptly.
     */
    private static final long CHUNK_BUDGET_NANOS = 12_000_000L;

    /** Below this many edits the work is done in one go, with no dialog at all. */
    static final int PROGRESS_THRESHOLD = 2_000;

    /** What became of a run. */
    record Outcome(int applied, int total, boolean cancelled) {
        boolean isComplete() {
            return !cancelled && applied == total;
        }
    }

    private final RSyntaxTextArea textArea;
    private final List<Replacement> plan;
    private final Consumer<Outcome> onDone;

    private JDialog dialog;
    private JProgressBar progressBar;
    private JLabel progressLabel;

    /** Next edit to apply; counts down, because the plan is applied back to front. */
    private int cursor;
    private int applied;
    private boolean cancelled;
    private boolean atomicEditOpen;

    private ChunkedReplacer(RSyntaxTextArea textArea, List<Replacement> plan, Consumer<Outcome> onDone) {
        this.textArea = textArea;
        this.plan = plan;
        this.onDone = onDone;
        this.cursor = plan.size() - 1;
    }

    /**
     * Applies {@code plan}, showing a modal progress dialog if it is large enough to be
     * worth explaining. {@code onDone} is called on the EDT either way.
     */
    static void apply(RSyntaxTextArea textArea, Component parent,
                      List<Replacement> plan, Consumer<Outcome> onDone) {
        ChunkedReplacer replacer = new ChunkedReplacer(textArea, plan, onDone);
        if (plan.size() <= PROGRESS_THRESHOLD) {
            replacer.runToCompletion();
        } else {
            replacer.runWithProgress(parent);
        }
    }

    /** The small case: one pass, no dialog, no yielding. */
    private void runToCompletion() {
        beginAtomicEdit();
        try {
            while (cursor >= 0) {
                applyOne();
            }
        } finally {
            endAtomicEdit();
        }
        onDone.accept(new Outcome(applied, plan.size(), false));
    }

    /**
     * The large case: schedule the first chunk, then show the dialog. The ordering
     * matters — {@code setVisible} on a modal dialog does not return until the dialog
     * closes, so the chunks must already be queued for its nested event loop to run.
     */
    private void runWithProgress(Component parent) {
        buildDialog(parent);
        beginAtomicEdit();
        scheduleChunk();
        dialog.setVisible(true);
    }

    private void scheduleChunk() {
        SwingUtilities.invokeLater(() -> {
            if (cancelled) {
                finish();
                return;
            }

            long deadline = System.nanoTime() + CHUNK_BUDGET_NANOS;
            while (cursor >= 0 && System.nanoTime() < deadline) {
                applyOne();
            }

            updateProgress();
            if (cursor < 0) {
                finish();
            } else {
                scheduleChunk();
            }
        });
    }

    /**
     * Applies the edit at the cursor.
     *
     * <p>A {@link BadLocationException} would mean the document no longer matches the
     * plan. That should be impossible — the dialog holds off every other edit — so it is
     * treated as a defect rather than smoothed over into a partial result.</p>
     */
    private void applyOne() {
        Replacement replacement = plan.get(cursor);
        try {
            Document document = textArea.getDocument();
            document.remove(replacement.start(), replacement.length());
            document.insertString(replacement.start(), replacement.text(), null);
        } catch (BadLocationException e) {
            throw new IllegalStateException(
                "Document changed under a replace plan at " + replacement.start(), e);
        }
        cursor--;
        applied++;
    }

    private void beginAtomicEdit() {
        textArea.beginAtomicEdit();
        atomicEditOpen = true;
    }

    /** Idempotent: the run can end through completion, cancellation or failure. */
    private void endAtomicEdit() {
        if (atomicEditOpen) {
            atomicEditOpen = false;
            textArea.endAtomicEdit();
        }
    }

    private void finish() {
        endAtomicEdit();
        if (dialog != null) {
            dialog.dispose();
        }
        onDone.accept(new Outcome(applied, plan.size(), cancelled));
    }

    private void updateProgress() {
        progressBar.setValue(applied);
        progressLabel.setText("Replacing " + applied + " of " + plan.size() + "…");
    }

    private void buildDialog(Component parent) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        dialog = owner instanceof Frame frame
            ? new JDialog(frame, "Replace All", Dialog.ModalityType.APPLICATION_MODAL)
            : new JDialog((Frame) null, "Replace All", Dialog.ModalityType.APPLICATION_MODAL);

        // The close button must not leave the run orphaned behind a dismissed dialog;
        // route it to the same cancellation the Cancel button uses.
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                cancelled = true;
            }
        });

        progressLabel = new JLabel("Replacing 0 of " + plan.size() + "…");
        progressBar = new JProgressBar(0, plan.size());
        progressBar.setValue(0);

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> cancelled = true);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(progressLabel, BorderLayout.NORTH);
        content.add(progressBar, BorderLayout.CENTER);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.LINE_AXIS));
        buttons.add(Box.createHorizontalGlue());
        buttons.add(cancelButton);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
    }
}
