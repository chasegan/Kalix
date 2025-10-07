package com.kalix.ide.cli;

import java.util.Optional;

/**
 * Enums and constants for the compact JSON STDIO protocol.
 */
public class JsonStdioTypes {

    /**
     * Message types sent from kalixcli to frontend (compact protocol).
     */
    public enum SystemMessageType {
        READY("rdy"),
        BUSY("bsy"),
        PROGRESS("prg"),
        RESULT("res"),
        STOPPED("stp"),
        ERROR("err"),
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
     * Message types sent from frontend to kalixcli (compact protocol).
     */
    public enum CommandMessageType {
        COMMAND("cmd"),
        STOP("stp"),
        QUERY("query"),
        TERMINATE("term");

        private final String value;

        CommandMessageType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    /**
     * Task types for progress messages.
     */
    public enum TaskType {
        SIMULATION("sim"),
        CALIBRATION("cal"),
        LOADING("load"),
        PROCESSING("proc"),
        BUILDING("build");

        private final String value;

        TaskType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static Optional<TaskType> fromString(String value) {
            for (TaskType type : values()) {
                if (type.value.equals(value)) {
                    return Optional.of(type);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Return codes for ready messages.
     */
    public static class ReturnCodes {
        public static final int SUCCESS = 0;
        public static final int ERROR = 1;
        public static final int INTERRUPTED = 2;

        public static String getDescription(int code) {
            switch (code) {
                case SUCCESS: return "Success";
                case ERROR: return "Error";
                case INTERRUPTED: return "Interrupted";
                default: return "Unknown (" + code + ")";
            }
        }
    }
}