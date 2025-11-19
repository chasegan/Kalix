package com.kalix.ide.editor.commands;

import javax.swing.KeyStroke;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Registry for all available editor commands.
 * Commands can be looked up by context or keyboard shortcut.
 */
public class CommandRegistry {

    private final List<EditorCommand> commands = new ArrayList<>();

    /**
     * Registers a command with the registry.
     *
     * @param command The command to register
     */
    public void register(EditorCommand command) {
        commands.add(command);
    }

    /**
     * Gets all commands that are applicable in the given context.
     *
     * @param context The current editor context
     * @return List of applicable commands
     */
    public List<EditorCommand> getApplicableCommands(EditorContext context) {
        return commands.stream()
            .filter(cmd -> cmd.isApplicable(context))
            .collect(Collectors.toList());
    }

    /**
     * Finds a command by keyboard shortcut.
     *
     * @param keyStroke The keyboard shortcut
     * @param context   The current context (command must also be applicable)
     * @return Optional containing the command, or empty if not found
     */
    public Optional<EditorCommand> findByKeyStroke(KeyStroke keyStroke, EditorContext context) {
        return commands.stream()
            .filter(cmd -> cmd.isApplicable(context))
            .filter(cmd -> cmd.getMetadata().getKeyboardShortcut()
                .map(ks -> ks.equals(keyStroke))
                .orElse(false))
            .findFirst();
    }

    /**
     * Gets all registered commands.
     *
     * @return List of all commands
     */
    public List<EditorCommand> getAllCommands() {
        return new ArrayList<>(commands);
    }
}
