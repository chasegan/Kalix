package com.kalix.ide.editor.commands;

/**
 * Base interface for all editor commands.
 * Commands are context-aware actions that can be performed on the editor content.
 */
public interface EditorCommand {

    /**
     * Gets the metadata describing this command.
     *
     * @return Command metadata
     */
    CommandMetadata getMetadata();

    /**
     * Checks if this command is applicable in the given context.
     *
     * @param context The current editor context
     * @return true if the command can be executed in this context
     */
    boolean isApplicable(EditorContext context);

    /**
     * Executes this command in the given context.
     *
     * @param context  The current editor context
     * @param executor The command executor to use for performing operations
     */
    void execute(EditorContext context, CommandExecutor executor);
}
