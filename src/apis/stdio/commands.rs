use std::collections::HashMap;
use std::sync::Arc;
use crate::apis::stdio::messages::{CommandSpec, ParameterSpec, ProgressInfo};
use crate::apis::stdio::session::Session;
use crate::io::ini_model_io::IniModelIO;

pub trait Command: Send + Sync {
    fn name(&self) -> &str;
    fn description(&self) -> &str;
    fn parameters(&self) -> Vec<ParameterSpec>;
    fn interruptible(&self) -> bool;
    
    fn execute(
        &self,
        session: &mut Session,
        params: serde_json::Value,
        progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError>;

    fn get_spec(&self) -> CommandSpec {
        CommandSpec {
            name: self.name().to_string(),
            description: self.description().to_string(),
            parameters: self.parameters(),
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum CommandError {
    #[error("Invalid parameters: {0}")]
    InvalidParameters(String),
    
    #[error("Command execution error: {0}")]
    ExecutionError(String),
    
    #[error("Task was interrupted")]
    Interrupted,
    
    #[error("Model not loaded")]
    ModelNotLoaded,
    
    #[error("Data not loaded")]
    DataNotLoaded,
    
    #[error("IO error: {0}")]
    IoError(String),
}

pub struct CommandRegistry {
    commands: HashMap<String, Arc<dyn Command>>,
}

impl CommandRegistry {
    pub fn new() -> Self {
        let mut registry = Self {
            commands: HashMap::new(),
        };
        
        // Register built-in commands
        registry.register(Arc::new(GetVersionCommand));
        registry.register(Arc::new(GetStateCommand));
        registry.register(Arc::new(TestProgressCommand));
        registry.register(Arc::new(LoadModelFileCommand));
        registry.register(Arc::new(LoadModelStringCommand));
        
        registry
    }
    
    pub fn register(&mut self, command: Arc<dyn Command>) {
        self.commands.insert(command.name().to_string(), command);
    }
    
    pub fn get_command(&self, name: &str) -> Option<Arc<dyn Command>> {
        self.commands.get(name).cloned()
    }
    
    pub fn get_all_specs(&self) -> Vec<CommandSpec> {
        self.commands.values().map(|cmd| cmd.get_spec()).collect()
    }
    
    pub fn list_commands(&self) -> Vec<&str> {
        self.commands.keys().map(|s| s.as_str()).collect()
    }
}

// Built-in commands

pub struct GetVersionCommand;

impl Command for GetVersionCommand {
    fn name(&self) -> &str {
        "get_version"
    }
    
    fn description(&self) -> &str {
        "Get kalixcli version information"
    }
    
    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![]
    }
    
    fn interruptible(&self) -> bool {
        false
    }
    
    fn execute(
        &self,
        _session: &mut Session,
        _params: serde_json::Value,
        _progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        Ok(serde_json::json!({
            "version": "0.1.0",
            "build_date": "2025-09-08",
            "features": ["stdio", "modeling", "calibration"]
        }))
    }
}

pub struct GetStateCommand;

impl Command for GetStateCommand {
    fn name(&self) -> &str {
        "get_state"
    }
    
    fn description(&self) -> &str {
        "Get current kalixcli state information"
    }
    
    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![]
    }
    
    fn interruptible(&self) -> bool {
        false
    }
    
    fn execute(
        &self,
        session: &mut Session,
        _params: serde_json::Value,
        _progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        let state_info = session.get_state_info();
        Ok(serde_json::to_value(state_info).unwrap())
    }
}

pub struct TestProgressCommand;

impl Command for TestProgressCommand {
    fn name(&self) -> &str {
        "test_progress"
    }
    
    fn description(&self) -> &str {
        "Test command that demonstrates progress reporting and interruption"
    }
    
    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![
            ParameterSpec {
                name: "duration_seconds".to_string(),
                param_type: "integer".to_string(),
                required: false,
                default: Some(serde_json::json!(10)),
            }
        ]
    }
    
    fn interruptible(&self) -> bool {
        true
    }
    
    fn execute(
        &self,
        session: &mut Session,
        params: serde_json::Value,
        progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        let duration = params.get("duration_seconds")
            .and_then(|v| v.as_i64())
            .unwrap_or(10) as u64;
        
        let total_steps = 100;
        let step_duration = std::time::Duration::from_millis(duration * 1000 / total_steps);
        
        for i in 0..=total_steps {
            // Check for interrupt
            if session.check_interrupt() {
                return Err(CommandError::Interrupted);
            }
            
            // Send progress update
            let progress = ProgressInfo {
                percent_complete: i as f64,
                current_step: format!("Step {} of {}", i, total_steps),
                estimated_remaining: if i < total_steps {
                    let remaining_steps = total_steps - i;
                    let remaining_seconds = remaining_steps * step_duration.as_millis() as u64 / 1000;
                    Some(format!("00:{:02}:{:02}", remaining_seconds / 60, remaining_seconds % 60))
                } else {
                    None
                },
                details: Some(serde_json::json!({
                    "current_step": i,
                    "total_steps": total_steps,
                    "duration_per_step_ms": step_duration.as_millis()
                })),
            };
            
            progress_sender(progress);
            
            if i < total_steps {
                std::thread::sleep(step_duration);
            }
        }
        
        Ok(serde_json::json!({
            "completed": true,
            "total_steps": total_steps,
            "duration_seconds": duration
        }))
    }
}

pub struct LoadModelFileCommand;

impl Command for LoadModelFileCommand {
    fn name(&self) -> &str {
        "load_model_file"
    }
    
    fn description(&self) -> &str {
        "Load a hydrological model from a file path"
    }
    
    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![
            ParameterSpec {
                name: "model_path".to_string(),
                param_type: "string".to_string(),
                required: true,
                default: None,
            },
            ParameterSpec {
                name: "validation".to_string(),
                param_type: "boolean".to_string(),
                required: false,
                default: Some(serde_json::json!(true)),
            }
        ]
    }
    
    fn interruptible(&self) -> bool {
        false
    }
    
    fn execute(
        &self,
        session: &mut Session,
        params: serde_json::Value,
        progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        // Extract parameters
        let model_path = params.get("model_path")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CommandError::InvalidParameters("model_path is required".to_string()))?;
        
        let validation = params.get("validation")
            .and_then(|v| v.as_bool())
            .unwrap_or(true);
        
        progress_sender(ProgressInfo {
            percent_complete: 10.0,
            current_step: format!("Loading model from file: {}", model_path),
            estimated_remaining: None,
            details: Some(serde_json::json!({
                "file_path": model_path,
                "validation_enabled": validation
            })),
        });
        
        // Load the model
        let ini_reader = IniModelIO::new();
        let model = ini_reader.read_model_file(model_path)
            .map_err(|e| CommandError::ExecutionError(format!("Failed to load model: {}", e)))?;
        
        progress_sender(ProgressInfo {
            percent_complete: 80.0,
            current_step: "Validating model structure".to_string(),
            estimated_remaining: None,
            details: None,
        });
        
        // Store the model in the session
        session.set_model(model);
        
        progress_sender(ProgressInfo {
            percent_complete: 100.0,
            current_step: "Model loaded successfully".to_string(),
            estimated_remaining: None,
            details: None,
        });
        
        let model_info = session.get_model()
            .map(|m| serde_json::json!({
                "nodes_count": m.nodes.len(),
                "inputs_count": m.inputs.len(),
                "outputs_count": m.outputs.len()
            }))
            .unwrap_or(serde_json::json!({}));
        
        Ok(serde_json::json!({
            "success": true,
            "model_path": model_path,
            "validation_enabled": validation,
            "model_info": model_info
        }))
    }
}

pub struct LoadModelStringCommand;

impl Command for LoadModelStringCommand {
    fn name(&self) -> &str {
        "load_model_string"
    }
    
    fn description(&self) -> &str {
        "Load a hydrological model from an INI string"
    }
    
    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![
            ParameterSpec {
                name: "model_ini".to_string(),
                param_type: "string".to_string(),
                required: true,
                default: None,
            },
            ParameterSpec {
                name: "validation".to_string(),
                param_type: "boolean".to_string(),
                required: false,
                default: Some(serde_json::json!(true)),
            }
        ]
    }
    
    fn interruptible(&self) -> bool {
        false
    }
    
    fn execute(
        &self,
        session: &mut Session,
        params: serde_json::Value,
        progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        // Extract parameters
        let model_ini = params.get("model_ini")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CommandError::InvalidParameters("model_ini is required".to_string()))?;
        
        let validation = params.get("validation")
            .and_then(|v| v.as_bool())
            .unwrap_or(true);
        
        progress_sender(ProgressInfo {
            percent_complete: 10.0,
            current_step: format!("Parsing INI model ({} bytes)", model_ini.len()),
            estimated_remaining: None,
            details: Some(serde_json::json!({
                "ini_length": model_ini.len(),
                "validation_enabled": validation
            })),
        });
        
        // Load the model from string
        let ini_reader = IniModelIO::new();
        let model = ini_reader.read_model_string(model_ini)
            .map_err(|e| CommandError::ExecutionError(format!("Failed to parse model: {}", e)))?;
        
        progress_sender(ProgressInfo {
            percent_complete: 80.0,
            current_step: "Validating model structure".to_string(),
            estimated_remaining: None,
            details: None,
        });
        
        // Store the model in the session
        session.set_model(model);
        
        progress_sender(ProgressInfo {
            percent_complete: 100.0,
            current_step: "Model loaded successfully".to_string(),
            estimated_remaining: None,
            details: None,
        });
        
        let model_info = session.get_model()
            .map(|m| serde_json::json!({
                "nodes_count": m.nodes.len(),
                "inputs_count": m.inputs.len(),
                "outputs_count": m.outputs.len()
            }))
            .unwrap_or(serde_json::json!({}));
        
        Ok(serde_json::json!({
            "success": true,
            "ini_length": model_ini.len(),
            "validation_enabled": validation,
            "model_info": model_info
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_command_registry() {
        let registry = CommandRegistry::new();
        let commands = registry.list_commands();
        
        assert!(commands.contains(&"get_version"));
        assert!(commands.contains(&"get_state"));
        assert!(commands.contains(&"test_progress"));
        assert!(commands.contains(&"load_model_file"));
        assert!(commands.contains(&"load_model_string"));
    }

    #[test]
    fn test_get_version_command() {
        let cmd = GetVersionCommand;
        let mut session = Session::new();
        
        let result = cmd.execute(
            &mut session,
            serde_json::json!({}),
            Box::new(|_| {}),
        ).unwrap();
        
        assert_eq!(result["version"], "0.1.0");
    }
}