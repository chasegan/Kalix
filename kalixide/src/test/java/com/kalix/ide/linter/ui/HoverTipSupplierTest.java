package com.kalix.ide.linter.ui;

import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.schema.ParameterDefinition;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import javax.swing.ToolTipManager;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's hover tips go through Swing's ToolTipManager: this pins the
 * supplier's precedence, the HTML the two sources build (escaping included),
 * and the dismiss-delay idiom, all headless.
 */
class HoverTipSupplierTest {

    private static ValidationIssue error(int line, String message) {
        return new ValidationIssue(line, message, ValidationRule.Severity.ERROR, "rule");
    }

    private static ValidationIssue warning(int line, String message) {
        return new ValidationIssue(line, message, ValidationRule.Severity.WARNING, "rule");
    }

    @Test
    void issuesTakePrecedenceOverHelp() {
        HoverTipSupplier supplier = HoverTipSupplier.install(new RSyntaxTextArea());
        supplier.setIssueSource((line, offset) -> line == 3 ? "<html>issue</html>" : null);
        supplier.setHelpSource((line, offset) -> "<html>help</html>");

        assertEquals("<html>issue</html>", supplier.tipFor(3, 0));
        assertEquals("<html>help</html>", supplier.tipFor(4, 0));

        supplier.setHelpSource(null);
        assertNull(supplier.tipFor(4, 0));
        supplier.uninstall();
    }

    @Test
    void noSourcesMeansNoTip() {
        HoverTipSupplier supplier = HoverTipSupplier.install(new RSyntaxTextArea());
        assertNull(supplier.tipFor(1, 0));
        supplier.uninstall();
    }

    @Test
    void switchesOffTheLibrarysFocusableTips() {
        // Focusable tips call requestFocus() on the text area whenever they close,
        // which would steal keyboard focus on a mere hover.
        RSyntaxTextArea textArea = new RSyntaxTextArea();
        HoverTipSupplier supplier = HoverTipSupplier.install(textArea);
        assertFalse(textArea.getUseFocusableTips());
        supplier.uninstall();
    }

    @Test
    void dismissDelayIsRaisedInsideTheEditorAndRestoredOnExit() {
        ToolTipManager manager = ToolTipManager.sharedInstance();
        int original = manager.getDismissDelay();
        RSyntaxTextArea textArea = new RSyntaxTextArea();
        HoverTipSupplier supplier = HoverTipSupplier.install(textArea);
        try {
            MouseEvent enter = new MouseEvent(textArea, MouseEvent.MOUSE_ENTERED, 0, 0, 1, 1, 0, false);
            MouseEvent exit = new MouseEvent(textArea, MouseEvent.MOUSE_EXITED, 0, 0, 1, 1, 0, false);
            for (java.awt.event.MouseListener l : textArea.getMouseListeners()) {
                l.mouseEntered(enter);
            }
            assertEquals(HoverTipSupplier.EDITOR_DISMISS_DELAY_MS, manager.getDismissDelay());
            for (java.awt.event.MouseListener l : textArea.getMouseListeners()) {
                l.mouseExited(exit);
            }
            assertEquals(original, manager.getDismissDelay());
        } finally {
            supplier.uninstall();
            manager.setDismissDelay(original);
        }
    }

    @Test
    void issueTipStacksOneRowPerIssueAndEscapesMessages() {
        ConcurrentHashMap<Integer, List<ValidationIssue>> byLine = new ConcurrentHashMap<>();
        byLine.put(5, List.of(error(5, "Value must be <= 10"), warning(5, "Duplicate property 'a' in [data]")));
        IssueTooltips tips = new IssueTooltips(byLine);

        assertNull(tips.tipAt(4, 0));
        String html = tips.tipAt(5, 0);
        assertTrue(html.startsWith("<html>"));
        assertTrue(html.contains("Value must be &lt;= 10"), html);
        assertTrue(html.contains("[ERROR]"), html);
        assertTrue(html.contains("[WARNING]"), html);
        assertEquals(1, html.split("<br>").length - 1, "two issues, one line break between them");
    }

    @Test
    void propertyTipListsWhatTheSchemaKnows() {
        ParameterDefinition def = new ParameterDefinition();
        def.description = "Catchment area, km<sup>2</sup>";
        def.type = "number";
        def.min = 0.0;
        def.max = 2.5;
        def.count = 4;

        String html = PropertyTooltips.html("area", "required", def);
        assertTrue(html.contains("<b>area</b> <i>(required)</i>"), html);
        assertTrue(html.contains("Catchment area, km&lt;sup&gt;2&lt;/sup&gt;"), "description is text, not markup");
        assertTrue(html.contains("Type: <code>number</code>"), html);
        assertTrue(html.contains("Min: 0"), html);
        assertTrue(html.contains("Max: 2.5"), html);
        assertTrue(html.contains("Expected values: 4"), html);

        String bare = PropertyTooltips.html("ds_1", "downstream link", null);
        assertTrue(bare.contains("<b>ds_1</b> <i>(downstream link)</i>"), bare);
        assertFalse(bare.contains("Type:"), bare);
    }

    @Test
    void escapeHtmlCoversTheMarkupCharacters() {
        assertEquals("a &lt; b &amp;&amp; c &gt; &quot;d&quot;", HoverTipSupplier.escapeHtml("a < b && c > \"d\""));
        assertEquals("", HoverTipSupplier.escapeHtml(null));
    }
}
