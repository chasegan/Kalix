package com.kalix.ide.editor;

import com.kalix.ide.editor.autocomplete.KalixCompletionProvider;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.SchemaManager;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Supplier;

/**
 * Manages auto-completion lifecycle for the Kalix INI text editor.
 * Installs the AutoCompletion popup on the text area with context-aware completions
 * triggered by Ctrl+Space.
 */
public class AutoCompleteManager {

    private static final Logger logger = LoggerFactory.getLogger(AutoCompleteManager.class);

    private final RSyntaxTextArea textArea;
    private final SchemaManager schemaManager;
    private final Supplier<INIModelParser.ParsedModel> modelSupplier;
    private AutoCompletion autoCompletion;

    public AutoCompleteManager(RSyntaxTextArea textArea,
                               SchemaManager schemaManager,
                               Supplier<INIModelParser.ParsedModel> modelSupplier) {
        this.textArea = textArea;
        this.schemaManager = schemaManager;
        this.modelSupplier = modelSupplier;
    }

    /**
     * Installs auto-completion on the text area.
     */
    public void install() {
        LinterSchema schema = schemaManager.getCurrentSchema();
        if (schema == null) {
            logger.warn("Schema not available, auto-complete not installed");
            return;
        }

        KalixCompletionProvider provider = new KalixCompletionProvider(schema, modelSupplier);

        autoCompletion = new AutoCompletion(provider);
        autoCompletion.setShowDescWindow(true);
        autoCompletion.setAutoActivationEnabled(false);
        autoCompletion.setAutoCompleteSingleChoices(false);
        autoCompletion.setParameterAssistanceEnabled(false);

        // Ctrl+Space on all platforms (Cmd+Space is Spotlight on macOS)
        autoCompletion.setTriggerKey(
                KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));

        autoCompletion.install(textArea);
        logger.info("Auto-complete installed");
    }

    /**
     * Removes auto-completion from the text area.
     */
    public void dispose() {
        if (autoCompletion != null) {
            autoCompletion.uninstall();
            autoCompletion = null;
        }
    }
}
