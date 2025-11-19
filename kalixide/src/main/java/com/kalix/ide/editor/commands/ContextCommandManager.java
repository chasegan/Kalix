package com.kalix.ide.editor.commands;

import com.kalix.ide.linter.parsing.INIModelParser;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.List;
import java.util.function.Supplier;

/**
 * Manages context-aware editor commands.
 * Coordinates command registration, context detection, and execution.
 */
public class ContextCommandManager {

    private static final Logger logger = LoggerFactory.getLogger(ContextCommandManager.class);

    private final RSyntaxTextArea editor;
    private final JFrame parentFrame;
    private final Supplier<INIModelParser.ParsedModel> modelSupplier;

    private final CommandRegistry registry;
    private final ContextDetector detector;
    private final CommandExecutor executor;

    /**
     * Creates a new ContextCommandManager.
     *
     * @param editor        The text editor
     * @param parentFrame   Parent frame for dialogs
     * @param modelSupplier Supplier for the parsed model (may return null if parsing failed)
     */
    public ContextCommandManager(RSyntaxTextArea editor, JFrame parentFrame,
                                  Supplier<INIModelParser.ParsedModel> modelSupplier) {
        this.editor = editor;
        this.parentFrame = parentFrame;
        this.modelSupplier = modelSupplier;

        this.registry = new CommandRegistry();
        this.detector = new ContextDetector();
        this.executor = new CommandExecutor(editor, parentFrame);
    }

    /**
     * Initializes the command system and registers all available commands.
     */
    public void initialize() {
        registerCommands();
        logger.info("Context command system initialized with {} commands", registry.getAllCommands().size());
    }

    /**
     * Registers all available commands.
     */
    private void registerCommands() {
        // Register rename command - pass supplier, not the model itself
        registry.register(new RenameNodeCommand(modelSupplier, parentFrame));

        // Future commands will be registered here:
        // registry.register(new DeleteNodeCommand(...));
        // registry.register(new DuplicateNodeCommand(...));
        // etc.
    }

    /**
     * Gets applicable commands for the current cursor position.
     * Called when showing a context menu.
     *
     * @return List of applicable commands
     */
    public List<EditorCommand> getApplicableCommands() {
        // Get current context
        EditorContext context = detectCurrentContext();

        // Find applicable commands
        return registry.getApplicableCommands(context);
    }

    /**
     * Executes a command at the current cursor position.
     *
     * @param command The command to execute
     */
    public void executeCommand(EditorCommand command) {
        try {
            // Get current context
            EditorContext context = detectCurrentContext();

            // Verify command is still applicable (shouldn't change, but safety check)
            if (!command.isApplicable(context)) {
                logger.warn("Command {} is no longer applicable", command.getMetadata().getId());
                return;
            }

            // Execute the command
            command.execute(context, executor);

        } catch (Exception e) {
            logger.error("Error executing command: " + command.getMetadata().getId(), e);
            JOptionPane.showMessageDialog(
                parentFrame,
                "Error executing command: " + e.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    /**
     * Detects the current editing context.
     *
     * @return The current EditorContext
     */
    private EditorContext detectCurrentContext() {
        int caretPos = editor.getCaretPosition();
        String text = editor.getText();
        String selection = editor.getSelectedText();
        INIModelParser.ParsedModel model = modelSupplier.get();

        return detector.detectContext(caretPos, text, selection, model);
    }
}
