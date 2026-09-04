package com.kalix.ide.document;

import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.Test;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #388: after the optimiser's "Copy to Main Window" (a programmatic
 * {@code setText}), Table View showed the parameter values from before the copy.
 * The editor withheld programmatic replacements from its external document
 * listeners, so the document never invalidated the memoised parse that Table
 * View, autocomplete and the context menu read from.
 */
class KalixDocumentTextReplacementTest {

    private static final String BEFORE = """
            [node.catchment]
            type = sacramento
            loc = 1, 2
            params = 1, 2, 3
            """;

    private static final String AFTER = BEFORE.replace("params = 1, 2, 3", "params = 4, 5, 6");

    @Test
    void parsedModelReflectsAProgrammaticReplacement() {
        KalixDocument document = new KalixDocument();
        document.setText(BEFORE);
        INIModelParser.ParsedModel first = document.getModelSupplier().get();
        assertEquals("1, 2, 3", paramsOf(first));

        document.setText(AFTER); // what the optimiser's copy-back does
        INIModelParser.ParsedModel second = document.getModelSupplier().get();

        assertNotSame(first, second, "the memoised parse must be invalidated by the replacement");
        assertEquals("4, 5, 6", paramsOf(second));
    }

    @Test
    void unchangedTextStillReusesTheMemoisedParse() {
        KalixDocument document = new KalixDocument();
        document.setText(BEFORE);
        assertSame(document.getModelSupplier().get(), document.getModelSupplier().get());
    }

    @Test
    void externalListenersHearProgrammaticReplacementsButDirtyStateDoesNot() {
        EnhancedTextEditor editor = new EnhancedTextEditor();
        AtomicInteger events = new AtomicInteger();
        editor.addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { events.incrementAndGet(); }
            @Override public void removeUpdate(DocumentEvent e) { events.incrementAndGet(); }
            @Override public void changedUpdate(DocumentEvent e) { events.incrementAndGet(); }
        });

        editor.setText(BEFORE);
        assertTrue(events.get() > 0, "a programmatic replacement is still a change to the text");
        assertFalse(editor.isDirty(), "setText is a load, not an edit: it resets the dirty state");
    }

    private static String paramsOf(INIModelParser.ParsedModel model) {
        return model.getNodes().get("catchment").getProperties().get("params").getValue();
    }
}
