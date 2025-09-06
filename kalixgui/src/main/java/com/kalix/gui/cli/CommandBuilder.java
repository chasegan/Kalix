package com.kalix.gui.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds CLI commands from API specifications with parameter validation.
 * Provides a fluent interface for constructing type-safe CLI calls.
 */
public class CommandBuilder {
    
    private final ApiModel.ApiSpec apiSpec;
    private final ApiModel.Command command;
    private final List<String> commandParts;
    private final Map<String, String> parameters;
    private final List<String> positionalArgs;
    private final Set<String> errors;
    private final CliLogger logger;
    
    /**
     * Creates a new CommandBuilder for a specific command.
     * 
     * @param apiSpec The API specification
     * @param commandName The name of the command to build
     * @throws IllegalArgumentException if the command is not found in the API
     */
    public CommandBuilder(ApiModel.ApiSpec apiSpec, String commandName) {
        this.apiSpec = apiSpec;
        this.command = ApiDiscovery.findCommand(apiSpec, commandName)
            .orElseThrow(() -> new IllegalArgumentException("Command not found: " + commandName));
        this.commandParts = new ArrayList<>();
        this.parameters = new LinkedHashMap<>();
        this.positionalArgs = new ArrayList<>();
        this.errors = new LinkedHashSet<>();
        this.logger = CliLogger.getInstance();
        
        // Add the command name
        commandParts.add(commandName);
    }
    
    /**
     * Result of building a command.
     */
    public static class BuildResult {
        private final boolean valid;
        private final List<String> commandArgs;
        private final Set<String> errors;
        private final String commandString;
        
        public BuildResult(boolean valid, List<String> commandArgs, Set<String> errors) {
            this.valid = valid;
            this.commandArgs = new ArrayList<>(commandArgs);
            this.errors = new LinkedHashSet<>(errors);
            this.commandString = String.join(" ", commandArgs);
        }
        
        public boolean isValid() { return valid; }
        public List<String> getCommandArgs() { return new ArrayList<>(commandArgs); }
        public Set<String> getErrors() { return new LinkedHashSet<>(errors); }
        public String getCommandString() { return commandString; }
        
        @Override
        public String toString() {
            return valid ? "Valid[" + commandString + "]" : "Invalid[" + errors.size() + " errors]";
        }
    }
    
    /**
     * Sets a parameter by name with validation.
     * 
     * @param parameterName The parameter name
     * @param value The parameter value
     * @return this CommandBuilder for chaining
     */
    public CommandBuilder withParameter(String parameterName, String value) {
        if (parameterName == null || parameterName.trim().isEmpty()) {
            errors.add("Parameter name cannot be null or empty");
            return this;
        }
        
        if (value == null) {
            errors.add("Parameter value cannot be null for: " + parameterName);
            return this;
        }
        
        // Find the parameter in the command specification
        Optional<ApiModel.CommandArgument> argOpt = command.getArgs().stream()
            .filter(arg -> parameterName.equals(arg.getName()))
            .findFirst();
        
        if (argOpt.isEmpty()) {
            errors.add("Unknown parameter: " + parameterName);
            return this;
        }
        
        ApiModel.CommandArgument arg = argOpt.get();
        
        // Validate the parameter value
        if (!validateParameterValue(arg, value)) {
            return this; // Error already added by validateParameterValue
        }
        
        parameters.put(parameterName, value);
        logger.debug("Added parameter: " + parameterName + " = " + value);
        
        return this;
    }
    
    /**
     * Sets a flag parameter (boolean flag).
     * 
     * @param parameterName The parameter name
     * @param enabled Whether the flag should be enabled
     * @return this CommandBuilder for chaining
     */
    public CommandBuilder withFlag(String parameterName, boolean enabled) {
        if (!enabled) {
            // Remove the parameter if disabled
            parameters.remove(parameterName);
            return this;
        }
        
        // Find the parameter in the command specification
        Optional<ApiModel.CommandArgument> argOpt = command.getArgs().stream()
            .filter(arg -> parameterName.equals(arg.getName()))
            .findFirst();
        
        if (argOpt.isEmpty()) {
            errors.add("Unknown flag parameter: " + parameterName);
            return this;
        }
        
        ApiModel.CommandArgument arg = argOpt.get();
        if (!arg.isFlag()) {
            errors.add("Parameter is not a flag: " + parameterName);
            return this;
        }
        
        parameters.put(parameterName, ""); // Empty value for flags
        logger.debug("Added flag: " + parameterName);
        
        return this;
    }
    
    /**
     * Sets a file path parameter with validation.
     * 
     * @param parameterName The parameter name
     * @param filePath The file path
     * @param mustExist Whether the file must exist
     * @return this CommandBuilder for chaining
     */
    public CommandBuilder withFile(String parameterName, String filePath, boolean mustExist) {
        if (filePath == null || filePath.trim().isEmpty()) {
            errors.add("File path cannot be null or empty for: " + parameterName);
            return this;
        }
        
        Path path = Paths.get(filePath);
        
        if (mustExist && !Files.exists(path)) {
            errors.add("File does not exist: " + filePath + " (for parameter: " + parameterName + ")");
            return this;
        }
        
        return withParameter(parameterName, path.toAbsolutePath().toString());
    }
    
    /**
     * Adds a positional argument.
     * 
     * @param value The positional argument value
     * @return this CommandBuilder for chaining
     */
    public CommandBuilder withPositionalArg(String value) {
        if (value != null) {
            positionalArgs.add(value);
            logger.debug("Added positional arg: " + value);
        }
        return this;
    }
    
    /**
     * Sets multiple parameters at once.
     * 
     * @param params Map of parameter names to values
     * @return this CommandBuilder for chaining
     */
    public CommandBuilder withParameters(Map<String, String> params) {
        if (params != null) {
            params.forEach(this::withParameter);
        }
        return this;
    }
    
    /**
     * Validates a parameter value against its specification.
     */
    private boolean validateParameterValue(ApiModel.CommandArgument arg, String value) {
        // Check possible values if specified
        if (arg.hasPossibleValues()) {
            if (!arg.getPossibleValues().contains(value)) {
                errors.add("Invalid value for " + arg.getName() + ": " + value + 
                    ". Allowed values: " + String.join(", ", arg.getPossibleValues()));
                return false;
            }
        }
        
        // Additional validation based on parameter name patterns
        String paramName = arg.getName().toLowerCase();
        
        if (paramName.contains("file") || paramName.contains("path")) {
            // Basic path validation
            try {
                Paths.get(value); // This will throw if invalid path
            } catch (Exception e) {
                errors.add("Invalid file path for " + arg.getName() + ": " + value);
                return false;
            }
        }
        
        if (paramName.contains("iteration") || paramName.contains("count") || paramName.contains("number")) {
            // Numeric validation
            try {
                int num = Integer.parseInt(value);
                if (num < 0) {
                    errors.add("Negative value not allowed for " + arg.getName() + ": " + value);
                    return false;
                }
            } catch (NumberFormatException e) {
                errors.add("Invalid numeric value for " + arg.getName() + ": " + value);
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Validates that all required parameters are provided.
     */
    private void validateRequiredParameters() {
        List<ApiModel.CommandArgument> requiredArgs = command.getRequiredArgs();
        
        for (ApiModel.CommandArgument arg : requiredArgs) {
            if (!parameters.containsKey(arg.getName()) && !isProvidedAsPositional(arg)) {
                errors.add("Required parameter missing: " + arg.getName() + " - " + arg.getHelp());
            }
        }
    }
    
    /**
     * Checks if a parameter is provided as a positional argument.
     */
    private boolean isProvidedAsPositional(ApiModel.CommandArgument arg) {
        // This is a simplified check - in practice, you'd need more sophisticated
        // logic to match positional args to parameter specifications
        return !arg.isFlag() && !positionalArgs.isEmpty();
    }
    
    /**
     * Builds the command with validation.
     * 
     * @return BuildResult containing the command or validation errors
     */
    public BuildResult build() {
        // Validate required parameters
        validateRequiredParameters();
        
        if (!errors.isEmpty()) {
            logger.warn("Command validation failed with " + errors.size() + " errors:");
            errors.forEach(error -> logger.warn("  " + error));
            return new BuildResult(false, List.of(), errors);
        }
        
        // Build the command arguments
        List<String> args = new ArrayList<>(commandParts);
        
        // Add flag parameters (no values)
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String paramName = entry.getKey();
            String paramValue = entry.getValue();
            
            // Find the argument specification
            Optional<ApiModel.CommandArgument> argOpt = command.getArgs().stream()
                .filter(arg -> paramName.equals(arg.getName()))
                .findFirst();
            
            if (argOpt.isPresent()) {
                ApiModel.CommandArgument arg = argOpt.get();
                
                // Add the flag
                String preferredFlag = arg.getPreferredFlag();
                if (preferredFlag != null) {
                    args.add(preferredFlag);
                    
                    // Add value if it's not just a boolean flag
                    if (!paramValue.isEmpty()) {
                        args.add(paramValue);
                    }
                }
            }
        }
        
        // Add positional arguments at the end
        args.addAll(positionalArgs);
        
        logger.debug("Built command: " + String.join(" ", args));
        return new BuildResult(true, args, errors);
    }
    
    /**
     * Gets information about the command being built.
     */
    public String getCommandInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Command: ").append(command.getName()).append("\n");
        sb.append("Description: ").append(command.getAbout()).append("\n");
        
        if (command.hasArguments()) {
            sb.append("Parameters:\n");
            for (ApiModel.CommandArgument arg : command.getArgs()) {
                sb.append("  ").append(arg.getName());
                if (arg.isRequired()) {
                    sb.append(" [required]");
                }
                if (arg.isFlag()) {
                    sb.append(" (").append(String.join(", ", arg.getAllFlags())).append(")");
                }
                sb.append(": ").append(arg.getHelp()).append("\n");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Gets current parameter values for debugging.
     */
    public String getCurrentParameters() {
        if (parameters.isEmpty() && positionalArgs.isEmpty()) {
            return "No parameters set";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("Current parameters:\n");
        
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
        }
        
        if (!positionalArgs.isEmpty()) {
            sb.append("Positional arguments: ").append(String.join(", ", positionalArgs)).append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Factory method to create a CommandBuilder for simulation.
     */
    public static CommandBuilder forSimulation(ApiModel.ApiSpec apiSpec) {
        return new CommandBuilder(apiSpec, "sim");
    }
    
    /**
     * Factory method to create a CommandBuilder for calibration.
     */
    public static CommandBuilder forCalibration(ApiModel.ApiSpec apiSpec) {
        return new CommandBuilder(apiSpec, "calibrate");
    }
    
    /**
     * Factory method to create a CommandBuilder for testing.
     */
    public static CommandBuilder forTest(ApiModel.ApiSpec apiSpec) {
        return new CommandBuilder(apiSpec, "test");
    }
    
    /**
     * Validates a command before execution without building it.
     */
    public List<String> validateOnly() {
        validateRequiredParameters();
        return new ArrayList<>(errors);
    }
    
    /**
     * Clears all parameters and errors.
     */
    public CommandBuilder reset() {
        parameters.clear();
        positionalArgs.clear();
        errors.clear();
        return this;
    }
}