package com.kalix.ide.preferences.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for preference pages: a titled panel plus the shared free-text
 * commit mechanism.
 *
 * <p>Free-text fields do not save per keystroke (each write serializes the whole
 * preference file, and half-typed values are not meaningful); instead each field
 * registers a commit via {@link #commitOnFocusLostAndClose(JTextField, Runnable)},
 * which also runs it on Enter and on focus-lost. Commits skip writing when the
 * value is unchanged, so running them repeatedly is free.
 */
public abstract class AbstractPreferencePage extends JPanel implements PreferencePage {

    /** Commits for free-text fields, run when the dialog closes. */
    private final List<Runnable> pendingTextCommits = new ArrayList<>();

    protected AbstractPreferencePage(String title) {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder(title));
    }

    @Override
    public JComponent component() {
        return this;
    }

    @Override
    public final void commitPendingEdits() {
        pendingTextCommits.forEach(Runnable::run);
    }

    /** Creates the standard GridBagLayout form panel with the standard padding. */
    protected JPanel createFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        return panel;
    }

    /**
     * Wires a free-text field to commit on Enter and on focus-lost, and registers
     * the commit to run when the dialog closes. The commit itself must skip
     * writing when the value is unchanged.
     */
    protected void commitOnFocusLostAndClose(JTextField field, Runnable commit) {
        field.addActionListener(e -> commit.run());
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commit.run();
            }
        });
        pendingTextCommits.add(commit);
    }
}
