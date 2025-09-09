package com.kalix.gui.cli;

import java.util.Optional;

/**
 * Enums and constants for the JSON STDIO protocol.
 */
public class JsonStdioTypes {
    
    /**
     * Message types sent from kalixcli to frontend.
     */
    public enum SystemMessageType {
        READY("ready"),
        BUSY("busy"),
        PROGRESS("progress"),
        RESULT("result"),
        STOPPED("stopped"),
        ERROR("error"),
        LOG("log");
        
        private final String value;
        
        SystemMessageType(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
        
        public static Optional<SystemMessageType> fromString(String value) {
            for (SystemMessageType type : values()) {
                if (type.value.equals(value)) {
                    return Optional.of(type);
                }
            }
            return Optional.empty();
        }
    }
    
    /**
     * Message types sent from frontend to kalixcli.
     */
    public enum CommandMessageType {
        COMMAND("command"),
        STOP("stop"),
        QUERY("query"),
        TERMINATE("terminate");
        
        private final String value;
        
        CommandMessageType(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
    }
}