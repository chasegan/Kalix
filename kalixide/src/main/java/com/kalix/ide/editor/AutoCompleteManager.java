package com.kalix.ide.editor;

import com.kalix.ide.editor.autocomplete.InputDataRegistry;
import com.kalix.ide.editor.autocomplete.KalixCompletionProvider;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.SchemaManager;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JWindow;
import javax.swing.KeyStroke;
import java.awt.Dimension;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.lang.reflect.Field;
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
    private final Supplier<File> baseDirectorySupplier;
    private static final int POPUP_WIDTH = 500;
    private static final int POPUP_HEIGHT = 300;

    private AutoCompletion autoCompletion;
    private InputDataRegistry inputDataRegistry;
    private boolean popupSized = false;

    public AutoCompleteManager(RSyntaxTextArea textArea,
                               SchemaManager schemaManager,
                               Supplier<INIModelParser.ParsedModel> modelSupplier,
                               Supplier<File> baseDirectorySupplier) {
        this.textArea = textArea;
        this.schemaManager = schemaManager;
        this.modelSupplier = modelSupplier;
        this.baseDirectorySupplier = baseDirectorySupplier;
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

        inputDataRegistry = new InputDataRegistry(baseDirectorySupplier);
        KalixCompletionProvider provider = new KalixCompletionProvider(schema, modelSupplier, inputDataRegistry);

        autoCompletion = new AutoCompletion(provider) {
            @Override
            protected int refreshPopupWindow() {
                int result = super.refreshPopupWindow();
                if (!popupSized) {
                    applyPopupSize(this);
                    // Hide and re-show so the description window is positioned
                    // relative to the resized popup, not the original packed size
                    hidePopupWindow();
                    result = super.refreshPopupWindow();
                }
                return result;
            }
        };
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
    /**
     * Applies the desired popup size by accessing the private popupWindow field
     * and setting the preferred size on its content pane before re-packing.
     * The library's setChoicesWindowSize uses setSize() after pack(), which the
     * layout manager overrides. Setting preferredSize + pack() is reliable.
     */
    private void applyPopupSize(AutoCompletion ac) {
        try {
            Field popupField = AutoCompletion.class.getDeclaredField("popupWindow");
            popupField.setAccessible(true);
            Object popup = popupField.get(ac);
            if (popup instanceof JWindow popupWindow) {
                popupWindow.getContentPane().setPreferredSize(new Dimension(POPUP_WIDTH, POPUP_HEIGHT));
                popupWindow.pack();
                popupSized = true;
            }
        } catch (Exception e) {
            logger.debug("Could not resize autocomplete popup: {}", e.getMessage());
        }
    }

    /**
     * Programmatically triggers the auto-completion popup.
     * This is equivalent to pressing Ctrl+Space.
     */
    public void showSuggestions() {
        if (autoCompletion != null) {
            autoCompletion.doCompletion();
        }
    }

    public void dispose() {
        if (autoCompletion != null) {
            autoCompletion.uninstall();
            autoCompletion = null;
        }
        if (inputDataRegistry != null) {
            inputDataRegistry.dispose();
            inputDataRegistry = null;
        }
    }
}
