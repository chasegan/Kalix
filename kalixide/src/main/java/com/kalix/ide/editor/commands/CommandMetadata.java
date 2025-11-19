package com.kalix.ide.editor.commands;

import javax.swing.KeyStroke;
import java.util.Optional;

/**
 * Metadata about an editor command.
 * Describes the command's identity, display information, and optional keyboard shortcut.
 */
public class CommandMetadata {

    private final String id;
    private final String displayName;
    private final String description;
    private final KeyStroke keyboardShortcut;
    private final String category;

    private CommandMetadata(Builder builder) {
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.keyboardShortcut = builder.keyboardShortcut;
        this.category = builder.category;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public Optional<KeyStroke> getKeyboardShortcut() {
        return Optional.ofNullable(keyboardShortcut);
    }

    public String getCategory() {
        return category;
    }

    /**
     * Builder for CommandMetadata.
     */
    public static class Builder {
        private String id;
        private String displayName;
        private String description = "";
        private KeyStroke keyboardShortcut = null;
        private String category = "General";

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder keyboardShortcut(KeyStroke shortcut) {
            this.keyboardShortcut = shortcut;
            return this;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public CommandMetadata build() {
            if (id == null || displayName == null) {
                throw new IllegalStateException("id and displayName are required");
            }
            return new CommandMetadata(this);
        }
    }
}
