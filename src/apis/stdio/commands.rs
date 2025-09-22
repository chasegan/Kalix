use std::collections::HashMap;
use std::sync::Arc;
use crate::apis::stdio::messages::{CommandSpec, ParameterSpec, ProgressInfo};
use crate::apis::stdio::session::Session;
use crate::io::ini_model_io::IniModelIO;
use chrono;

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

    #[error("Result not found: {0}")]
    ResultNotFound(String),
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
        registry.register(Arc::new(RunSimulationCommand));
        registry.register(Arc::new(GetResultCommand));
        registry.register(Arc::new(EchoCommand));
        
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
        
        progress_sender(ProgressInfo {
            percent_complete: 10.0,
            current_step: format!("Loading model from file: {}", model_path),
            estimated_remaining: None,
            details: Some(serde_json::json!({
                "file_path": model_path
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
        
        progress_sender(ProgressInfo {
            percent_complete: 10.0,
            current_step: format!("Parsing INI model ({} bytes)", model_ini.len()),
            estimated_remaining: None,
            details: Some(serde_json::json!({
                "ini_length": model_ini.len()
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
            "model_info": model_info
        }))
    }
}

pub struct EchoCommand;

impl Command for EchoCommand {
    fn name(&self) -> &str {
        "echo"
    }

    fn description(&self) -> &str {
        "Echo back the provided string"
    }

    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![
            ParameterSpec {
                name: "string".to_string(),
                param_type: "string".to_string(),
                required: true,
                default: None,
            },
        ]
    }

    fn interruptible(&self) -> bool {
        false
    }

    fn execute(
        &self,
        _session: &mut Session,
        params: serde_json::Value,
        _progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        // Extract parameters
        let string = params.get("string")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CommandError::InvalidParameters("string is required".to_string()))?;

        // Return the echoed string
        Ok(serde_json::json!({
            "echoed": string
        }))
    }
}

pub struct GetResultCommand;

impl Command for GetResultCommand {
    fn name(&self) -> &str {
        "get_result"
    }

    fn description(&self) -> &str {
        "Retrieve timeseries result data from the model"
    }

    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![
            ParameterSpec {
                name: "series_name".to_string(),
                param_type: "string".to_string(),
                required: true,
                default: None,
            },
            ParameterSpec {
                name: "format".to_string(),
                param_type: "string".to_string(),
                required: true,
                default: Some(serde_json::Value::String("csv".to_string())),
            },
        ]
    }

    fn interruptible(&self) -> bool {
        false
    }

    fn execute(
        &self,
        session: &mut Session,
        params: serde_json::Value,
        _progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        // Extract parameters
        let series_name = params.get("series_name")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CommandError::InvalidParameters("series_name is required".to_string()))?;

        let format = params.get("format")
            .and_then(|v| v.as_str())
            .unwrap_or("csv");

        if format != "csv" {
            return Err(CommandError::InvalidParameters("Only csv format is currently supported".to_string()));
        }

        // Get model and check if it exists
        let model = session.get_model()
            .ok_or(CommandError::ModelNotLoaded)?;

        // Find the series in the data cache
        let series_idx = model.data_cache.get_existing_series_idx(series_name)
            .ok_or_else(|| CommandError::ResultNotFound(format!("Timeseries '{}' not found in model results", series_name)))?;

        let timeseries = &model.data_cache.series[series_idx];

        // Build CSV data string
        let mut csv_data = String::new();

        // Add start timestamp and timestep
        let start_timestamp = chrono::DateTime::from_timestamp(timeseries.start_timestamp as i64, 0)
            .unwrap_or_else(|| chrono::Utc::now())
            .to_rfc3339();

        csv_data.push_str(&format!("{},{}", start_timestamp, timeseries.step_size));

        // Add values
        for value in &timeseries.values {
            csv_data.push_str(&format!(",{}", value));
        }

        // Build response
        Ok(serde_json::json!({
            "series_name": series_name,
            "format": format,
            "metadata": {
                "start_timestamp": start_timestamp,
                "timestep_seconds": timeseries.step_size,
                "total_points": timeseries.values.len(),
                "units": "unknown" // TODO: Add units to timeseries struct
            },
            "data": csv_data
        }))
    }
}

pub struct RunSimulationCommand;

impl Command for RunSimulationCommand {
    fn name(&self) -> &str {
        "run_simulation"
    }
    
    fn description(&self) -> &str {
        "Execute model simulation with loaded model and data"
    }
    
    fn parameters(&self) -> Vec<ParameterSpec> {
        vec![] // No parameters for now
    }
    
    fn interruptible(&self) -> bool {
        true // This is a long-running operation
    }
    
    fn execute(
        &self,
        session: &mut Session,
        _params: serde_json::Value,
        progress_sender: Box<dyn Fn(ProgressInfo) + Send>,
    ) -> Result<serde_json::Value, CommandError> {
        use std::time::Instant;
        use std::sync::Arc;
        use std::sync::atomic::{AtomicU64, Ordering};
        
        // Validation phase - 10%
        progress_sender(ProgressInfo {
            percent_complete: 10.0,
            current_step: "Validating model and data".to_string(),
            estimated_remaining: None,
            details: None,
        });
        
        // Get interrupt flag before getting mutable model reference
        let interrupt_flag = Arc::clone(&session.interrupt_flag);
        
        // Check if model is loaded
        let model = session.get_model_mut()
            .ok_or(CommandError::ModelNotLoaded)?;
        
        // Check if model has input data
        if model.inputs.is_empty() {
            return Err(CommandError::DataNotLoaded);
        }
        
        // Configuration phase - 20%
        progress_sender(ProgressInfo {
            percent_complete: 20.0,
            current_step: "Configuring model for simulation".to_string(),
            estimated_remaining: None,
            details: None,
        });
        
        model.configure();
        
        // Get simulation info for result
        let start_timestamp = model.configuration.sim_start_timestamp;
        let end_timestamp = model.configuration.sim_end_timestamp;
        let stepsize = model.configuration.sim_stepsize;
        let total_timesteps = ((end_timestamp - start_timestamp) / stepsize) + 1;
        
        // Track progress timing for rate limiting (max 1 update per second, 1 per percentage)
        let last_progress_time = Arc::new(std::sync::Mutex::new(Instant::now()));
        let last_progress_percent = Arc::new(AtomicU64::new(20));
        
        // Create progress callback for the model
        let progress_sender_clone = Arc::new(progress_sender);
        let progress_callback = {
            let last_time = Arc::clone(&last_progress_time);
            let last_percent = Arc::clone(&last_progress_percent);
            let sender = Arc::clone(&progress_sender_clone);
            
            Box::new(move |current_step: u64, total_steps: u64| {
                // Calculate percentage (20% to 90% range for simulation phase)
                let sim_progress = (current_step as f64 / total_steps as f64) * 70.0;
                let overall_progress = 20.0 + sim_progress;
                let overall_percent = overall_progress as u64;
                
                // Check rate limiting conditions
                let now = Instant::now();
                let should_update = {
                    let mut last_time_guard = last_time.lock().unwrap();
                    let time_elapsed = now.duration_since(*last_time_guard).as_secs() >= 1;
                    let percent_changed = overall_percent > last_percent.load(Ordering::Relaxed);
                    
                    if time_elapsed && percent_changed {
                        *last_time_guard = now;
                        last_percent.store(overall_percent, Ordering::Relaxed);
                        true
                    } else {
                        false
                    }
                };
                
                if should_update {
                    sender(ProgressInfo {
                        percent_complete: overall_progress,
                        current_step: format!("Running simulation - Processing timestep {} of {}", current_step + 1, total_steps),
                        estimated_remaining: None,
                        details: Some(serde_json::json!({
                            "current_timestep": current_step + 1,
                            "total_timesteps": total_steps,
                            "simulation_progress": format!("{:.1}%", sim_progress + (20.0/70.0)*100.0)
                        })),
                    });
                }
            })
        };
        
        // Simulation phase - 20% to 90%
        let simulation_start = Instant::now();
        
        // Run the simulation with interrupt checking
        let completed = model.run_with_interrupt(
            move || interrupt_flag.load(Ordering::Relaxed),
            Some(progress_callback)
        ).map_err(|e| CommandError::ExecutionError(format!("Simulation failed: {}", e)))?;
        
        let simulation_duration = simulation_start.elapsed();
        
        if !completed {
            // Simulation was interrupted
            return Err(CommandError::Interrupted);
        }
        
        // Results phase - 90% to 100%
        progress_sender_clone(ProgressInfo {
            percent_complete: 90.0,
            current_step: "Finalizing results".to_string(),
            estimated_remaining: None,
            details: None,
        });
        
        // Collect output information
        let outputs_generated: Vec<String> = model.outputs.clone();
        
        progress_sender_clone(ProgressInfo {
            percent_complete: 100.0,
            current_step: "Simulation completed".to_string(),
            estimated_remaining: None,
            details: None,
        });
        
        // Store simulation metadata in session results
        let simulation_metadata = serde_json::json!({
            "timestamp": chrono::Utc::now(),
            "duration_seconds": simulation_duration.as_secs(),
            "timesteps": total_timesteps,
            "outputs": outputs_generated.clone(),
        });
        session.store_result("last_simulation".to_string(), simulation_metadata);
        
        Ok(serde_json::json!({
            "simulation_completed": true,
            "timesteps_processed": total_timesteps,
            "outputs_generated": outputs_generated,
            "simulation_period": format!("{} to {}", 
                crate::tid::utils::u64_to_date_string(start_timestamp),
                crate::tid::utils::u64_to_date_string(end_timestamp)
            ),
            "execution_time_seconds": simulation_duration.as_secs(),
            "available_results": ["timeseries_data", "summary_statistics"]
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
        assert!(commands.contains(&"run_simulation"));
        assert!(commands.contains(&"get_result"));
        assert!(commands.contains(&"echo"));
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