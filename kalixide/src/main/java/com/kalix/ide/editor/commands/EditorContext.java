package com.kalix.ide.editor.commands;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Value object representing the current editing context.
 * Provides information about where the cursor is and what the user is editing.
 */
public class EditorContext {

    public enum ContextType {
        NODE_SECTION,      // Inside [node.xyz] section
        NODE_HEADER,       // On the [node.xyz] line itself
        PROPERTY,          // On a property line (key = value)
        TABLE_VALUE,       // Inside a multi-line table
        OUTPUT_REFERENCE,  // In [outputs] section
        INPUT_FILE,        // In [inputs] section on a file path line
        SECTION_HEADER,    // On a section header line
        CONSTANTS,         // In [constants] section
        UNKNOWN
    }

    private final int caretPosition;
    private final String selectedText;
    private final ContextType type;
    private final Map<String, Object> metadata;

    private EditorContext(Builder builder) {
        this.caretPosition = builder.caretPosition;
        this.selectedText = builder.selectedText;
        this.type = builder.type;
        this.metadata = new HashMap<>(builder.metadata);
    }

    public int getCaretPosition() {
        return caretPosition;
    }

    public String getSelectedText() {
        return selectedText;
    }

    public ContextType getType() {
        return type;
    }

    public Optional<String> getNodeName() {
        return Optional.ofNullable((String) metadata.get("nodeName"));
    }

    public Optional<String> getNodeType() {
        return Optional.ofNullable((String) metadata.get("nodeType"));
    }

    public Optional<String> getPropertyKey() {
        return Optional.ofNullable((String) metadata.get("propertyKey"));
    }

    public Optional<String> getSectionName() {
        return Optional.ofNullable((String) metadata.get("sectionName"));
    }

    public Optional<Integer> getLineNumber() {
        return Optional.ofNullable((Integer) metadata.get("lineNumber"));
    }

    public Optional<String> getInputFilePath() {
        return Optional.ofNullable((String) metadata.get("inputFilePath"));
    }

    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    /**
     * Builder for EditorContext.
     */
    public static class Builder {
        private int caretPosition;
        private String selectedText = "";
        private ContextType type = ContextType.UNKNOWN;
        private final Map<String, Object> metadata = new HashMap<>();

        public Builder caretPosition(int position) {
            this.caretPosition = position;
            return this;
        }

        public Builder selectedText(String text) {
            this.selectedText = text != null ? text : "";
            return this;
        }

        public Builder type(ContextType type) {
            this.type = type;
            return this;
        }

        public Builder nodeName(String nodeName) {
            this.metadata.put("nodeName", nodeName);
            return this;
        }

        public Builder nodeType(String nodeType) {
            this.metadata.put("nodeType", nodeType);
            return this;
        }

        public Builder propertyKey(String key) {
            this.metadata.put("propertyKey", key);
            return this;
        }

        public Builder sectionName(String sectionName) {
            this.metadata.put("sectionName", sectionName);
            return this;
        }

        public Builder lineNumber(int lineNumber) {
            this.metadata.put("lineNumber", lineNumber);
            return this;
        }

        public Builder inputFilePath(String filePath) {
            this.metadata.put("inputFilePath", filePath);
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public EditorContext build() {
            return new EditorContext(this);
        }
    }
}
