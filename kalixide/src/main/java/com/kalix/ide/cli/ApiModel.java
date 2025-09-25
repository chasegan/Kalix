package com.kalix.ide.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Data model classes for the kalixcli API structure.
 * These classes map to the JSON structure returned by 'kalixcli get-api'.
 */
public class ApiModel {
    
    /**
     * Root API specification returned by kalixcli get-api.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiSpec {
        @JsonProperty("about")
        private String about;
        
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("version")
        private String version;
        
        @JsonProperty("args")
        private List<CommandArgument> args;
        
        @JsonProperty("subcommands")
        private List<Command> subcommands;
        
        // Getters and setters
        public String getAbout() { return about; }
        public void setAbout(String about) { this.about = about; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        
        public List<CommandArgument> getArgs() { return args; }
        public void setArgs(List<CommandArgument> args) { this.args = args; }
        
        public List<Command> getSubcommands() { return subcommands; }
        public void setSubcommands(List<Command> subcommands) { this.subcommands = subcommands; }
        
        @Override
        public String toString() {
            return String.format("ApiSpec[name=%s, version=%s, subcommands=%d]", 
                name, version, subcommands != null ? subcommands.size() : 0);
        }
    }
    
    /**
     * Represents a command or subcommand in the API.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Command {
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("about")
        private String about;
        
        @JsonProperty("version")
        private String version;
        
        @JsonProperty("args")
        private List<CommandArgument> args;
        
        @JsonProperty("subcommands")
        private List<Command> subcommands;
        
        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getAbout() { return about; }
        public void setAbout(String about) { this.about = about; }
        
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        
        public List<CommandArgument> getArgs() { return args; }
        public void setArgs(List<CommandArgument> args) { this.args = args; }
        
        public List<Command> getSubcommands() { return subcommands; }
        public void setSubcommands(List<Command> subcommands) { this.subcommands = subcommands; }
        
        /**
         * Checks if this command has arguments.
         */
        public boolean hasArguments() {
            return args != null && !args.isEmpty();
        }
        
        /**
         * Checks if this command has subcommands.
         */
        public boolean hasSubcommands() {
            return subcommands != null && !subcommands.isEmpty();
        }
        
        /**
         * Gets required arguments only.
         */
        public List<CommandArgument> getRequiredArgs() {
            if (args == null) return List.of();
            return args.stream().filter(CommandArgument::isRequired).toList();
        }
        
        /**
         * Gets optional arguments only.
         */
        public List<CommandArgument> getOptionalArgs() {
            if (args == null) return List.of();
            return args.stream().filter(arg -> !arg.isRequired()).toList();
        }
        
        @Override
        public String toString() {
            return String.format("Command[name=%s, about=%s, args=%d, subcommands=%d]", 
                name, about, 
                args != null ? args.size() : 0,
                subcommands != null ? subcommands.size() : 0);
        }
    }
    
    /**
     * Represents a command argument or parameter.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommandArgument {
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("help")
        private String help;
        
        @JsonProperty("short")
        private String shortFlag;
        
        @JsonProperty("long")
        private String longFlag;
        
        @JsonProperty("required")
        private boolean required;
        
        @JsonProperty("multiple")
        private boolean multiple;
        
        @JsonProperty("possible_values")
        private List<String> possibleValues;
        
        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getHelp() { return help; }
        public void setHelp(String help) { this.help = help; }
        
        public String getShortFlag() { return shortFlag; }
        public void setShortFlag(String shortFlag) { this.shortFlag = shortFlag; }
        
        public String getLongFlag() { return longFlag; }
        public void setLongFlag(String longFlag) { this.longFlag = longFlag; }
        
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        
        public boolean isMultiple() { return multiple; }
        public void setMultiple(boolean multiple) { this.multiple = multiple; }
        
        public List<String> getPossibleValues() { return possibleValues; }
        public void setPossibleValues(List<String> possibleValues) { this.possibleValues = possibleValues; }
        
        /**
         * Checks if this argument has predefined possible values.
         */
        public boolean hasPossibleValues() {
            return possibleValues != null && !possibleValues.isEmpty();
        }
        
        /**
         * Checks if this argument is a flag (has short or long flag).
         */
        public boolean isFlag() {
            return shortFlag != null || longFlag != null;
        }
        
        /**
         * Gets the preferred flag (long if available, otherwise short).
         */
        public String getPreferredFlag() {
            if (longFlag != null) return "--" + longFlag;
            if (shortFlag != null) return "-" + shortFlag;
            return null;
        }
        
        /**
         * Gets all available flags for this argument.
         */
        public List<String> getAllFlags() {
            List<String> flags = new java.util.ArrayList<>();
            if (shortFlag != null) flags.add("-" + shortFlag);
            if (longFlag != null) flags.add("--" + longFlag);
            return flags;
        }
        
        @Override
        public String toString() {
            return String.format("CommandArgument[name=%s, required=%s, flags=[%s,%s], help=%s]", 
                name, required, shortFlag, longFlag, help);
        }
    }
}