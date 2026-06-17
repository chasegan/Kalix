package com.kalix.ide.editor.commands;

import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;
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
    private final Supplier<File> modelFileSupplier;
    private final Consumer<List<EnhancedTextEditor.LineReplacement>> replacementApplier;

    private final CommandRegistry registry;
    private final ContextDetector detector;
    private final CommandExecutor executor;

    /**
     * Creates a new ContextCommandManager.
     *
     * @param editor             The text editor
     * @param parentFrame        Parent frame for dialogs
     * @param modelSupplier      Supplier for the parsed model (may return null if parsing failed)
     * @param modelFileSupplier  Supplier for the current model file (may return null if no file loaded)
     * @param replacementApplier Callback for applying atomic text replacements
     */
    public ContextCommandManager(RSyntaxTextArea editor, JFrame parentFrame,
                                  Supplier<INIModelParser.ParsedModel> modelSupplier,
                                  Supplier<File> modelFileSupplier,
                                  Consumer<List<EnhancedTextEditor.LineReplacement>> replacementApplier) {
        this.editor = editor;
        this.parentFrame = parentFrame;
        this.modelSupplier = modelSupplier;
        this.modelFileSupplier = modelFileSupplier;
        this.replacementApplier = replacementApplier;

        this.registry = new CommandRegistry();
        this.detector = new ContextDetector();
        this.executor = new CommandExecutor(editor, parentFrame, replacementApplier);
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
        // Register plot command (appears at root level, before refactoring commands)
        registry.register(new PlotInputFileCommand(modelFileSupplier, parentFrame));

        // Register reveal in file manager command
        registry.register(new RevealInputFileCommand(modelFileSupplier, parentFrame));

        // Register insert file path command
        registry.register(new InsertFilePathCommand(modelFileSupplier, parentFrame));

        // Register rename command - pass supplier, not the model itself
        registry.register(new RenameNodeCommand(modelSupplier, parentFrame));
        registry.register(new RenameInputFileCommand(modelSupplier, parentFrame));
        registry.register(new RenameInputFileAliasCommand(modelSupplier, parentFrame));
        registry.register(new AddInputFileAliasCommand(modelSupplier, parentFrame));

        // Register table view command for editing params/dimensions
        registry.register(new OpenTableViewCommand(parentFrame, modelSupplier));

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
     * Gets the current editor context.
     * Useful for customizing menu item display names.
     *
     * @return The current EditorContext
     */
    public EditorContext getCurrentContext() {
        return detectCurrentContext();
    }

    /**
     * Executes a command at the current cursor position.
     *
     * <p>Intended for menu-click dispatch, where applicability was already
     * verified when the menu was built. The defensive re-check logs a warning
     * if the menu item turned stale between display and click.</p>
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
     * Looks up a command by id and executes it if applicable in the current
     * context; otherwise silently does nothing.
     *
     * <p>Intended for keyboard-shortcut dispatch, where the keystroke fires
     * regardless of context. A "not applicable" outcome is normal here (the
     * user pressed the hotkey somewhere it doesn't apply) and is not logged,
     * in contrast to {@link #executeCommand(EditorCommand)} which treats it
     * as a stale-menu anomaly.</p>
     *
     * @param commandId the id from the command's {@link CommandMetadata}
     */
    public void tryExecuteById(String commandId) {
        EditorCommand command = findCommandById(commandId);
        if (command == null) {
            logger.warn("No command registered with id '{}'", commandId);
            return;
        }
        try {
            EditorContext context = detectCurrentContext();
            if (command.isApplicable(context)) {
                command.execute(context, executor);
            }
        } catch (Exception e) {
            logger.error("Error executing command: " + commandId, e);
        }
    }

    private EditorCommand findCommandById(String commandId) {
        for (EditorCommand command : registry.getAllCommands()) {
            if (command.getMetadata().getId().equals(commandId)) {
                return command;
            }
        }
        return null;
    }

    /**
     * Installs key bindings for every registered command whose metadata
     * declares a {@link com.kalix.ide.editor.commands.CommandMetadata#getKeyboardShortcut keyboard shortcut}.
     *
     * <p>Each binding routes through {@link #tryExecuteById(String)}, so the
     * keystroke fires the command iff it is applicable in the current context.
     * The command's metadata is the single source of truth: the display hint
     * shown in the context menu and the actual keybinding are derived from
     * the same field, so they cannot drift.</p>
     *
     * <p>Should be called once after {@link #initialize()} has registered all
     * commands.</p>
     *
     * @param inputMap  the target input map (typically the text area's
     *                  {@code WHEN_FOCUSED} map)
     * @param actionMap the corresponding action map
     */
    public void installCommandShortcuts(InputMap inputMap, ActionMap actionMap) {
        for (EditorCommand command : registry.getAllCommands()) {
            command.getMetadata().getKeyboardShortcut().ifPresent(keyStroke -> {
                String commandId = command.getMetadata().getId();
                String actionKey = "command:" + commandId;
                inputMap.put(keyStroke, actionKey);
                actionMap.put(actionKey, new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        tryExecuteById(commandId);
                    }
                });
            });
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
