use std::time::Instant;
use std::io::Write;
use crate::apis::stdio::session::{Session, SessionError};
use crate::apis::stdio::transport::{Transport, TransportError};
use crate::apis::stdio::commands::{CommandRegistry, CommandError};
use crate::apis::stdio::messages::*;

pub fn run_stdio_session() -> Result<(), StdioError> {
    let mut session = Session::new();
    let transport = Transport::new();
    let registry = CommandRegistry::new();

    // Send initial ready message with return code 0 (success)
    let ready_msg = create_ready_message(session.id.clone(), 0);
    transport.send_message(&ready_msg)?;

    loop {
        match handle_session_loop(&mut session, &transport, &registry) {
            Ok(should_continue) => {
                if !should_continue {
                    break;
                }
            }
            Err(e) => {
                // Send error message and continue
                let error_msg = create_error_message(
                    session.id.clone(),
                    None,
                    format!("Session error: {}", e)
                );
                transport.send_message(&error_msg)?;

                // Reset to ready state after error
                let _ = session.set_ready();

                // Send ready message to indicate recovery
                let ready_msg = create_ready_message(session.id.clone(), 1); // Error code 1
                transport.send_message(&ready_msg)?;
            }
        }
    }

    Ok(())
}

fn handle_session_loop(
    session: &mut Session,
    transport: &Transport,
    registry: &CommandRegistry
) -> Result<bool, StdioError> {
    if session.is_ready() {
        // Block waiting for command when ready
        let msg = transport.receive_message_blocking()?;

        match msg.m.as_str() {
            MSG_COMMAND => {
                if let Some((command, params)) = extract_command_info(&msg) {
                    handle_command_message(session, transport, registry, command, params)?;
                } else {
                    send_error_message(session, transport, None, "Invalid command format".to_string())?;
                }
            }
            MSG_QUERY => {
                if let Some(query_type) = extract_query_type(&msg) {
                    handle_query_message(session, transport, query_type)?;
                } else {
                    send_error_message(session, transport, None, "Invalid query format".to_string())?;
                }
            }
            MSG_TERMINATE => {
                return Ok(false); // Signal to exit
            }
            _ => {
                send_error_message(session, transport, None, format!("Unknown message type: {}", msg.m))?;
            }
        }
    } else if session.is_busy() {
        // Check for interrupt messages while busy
        if let Some(msg) = transport.try_receive_message()? {
            match msg.m.as_str() {
                MSG_STOPPED => {
                    session.request_interrupt()?;
                }
                MSG_QUERY => {
                    if let Some(query_type) = extract_query_type(&msg) {
                        handle_query_message(session, transport, query_type)?;
                    }
                }
                MSG_TERMINATE => {
                    // Force interrupt and exit
                    let _ = session.request_interrupt();
                    return Ok(false);
                }
                _ => {
                    // Invalid message while busy, send error but continue
                    send_error_message(session, transport, None, format!("Cannot process '{}' message while busy", msg.m))?;
                }
            }
        }
    }

    Ok(true)
}

fn handle_command_message(
    session: &mut Session,
    transport: &Transport,
    registry: &CommandRegistry,
    command: String,
    parameters: serde_json::Value
) -> Result<(), StdioError> {
    // Find command in registry
    let command_spec = registry.get_command(&command)
        .ok_or_else(|| StdioError::UnknownCommand(command.clone()))?;

    let is_interruptible = command_spec.interruptible();

    // Send busy message
    let busy_msg = create_busy_message(
        session.id.clone(),
        command.clone(),
        is_interruptible
    );
    transport.send_message(&busy_msg)?;

    // Set session to busy
    session.set_busy(command.clone(), is_interruptible)?;

    let start_time = Instant::now();

    // Create progress callback for protocol messages
    let session_id = session.id.clone();
    let transport_clone = transport.stdout.clone();
    let progress_callback = Box::new(move |progress: ProgressInfo| {
        // Convert progress to protocol format and send
        let progress_msg = create_progress_message(
            session_id.clone(),
            progress.percent_complete as i64,
            100, // Total is 100 for percentage-based progress
            "sim".to_string(), // Default task type
        );

        if let Ok(json) = serde_json::to_string(&progress_msg) {
            if let Ok(mut stdout) = transport_clone.lock() {
                let _ = writeln!(stdout, "{}", json);
                let _ = stdout.flush();
            }
        }
    });

    // Execute command
    let result = command_spec.execute(session, parameters, progress_callback);

    let execution_time_ms = duration_to_ms(start_time.elapsed());

    // Send result or error based on outcome
    match result {
        Ok(ref command_result) => {
            // Determine result structure based on command type
            let result_data = match command.as_str() {
                "run_simulation" => {
                    // Extract simulation-specific data
                    if let Some(outputs) = command_result.get("outputs_generated").and_then(|v| v.as_array()) {
                        let output_names: Vec<String> = outputs.iter()
                            .filter_map(|v| v.as_str())
                            .map(|s| s.to_string())
                            .collect();

                        let timesteps = command_result.get("timesteps_processed")
                            .and_then(|v| v.as_i64())
                            .unwrap_or(0);

                        let period = command_result.get("simulation_period")
                            .and_then(|v| v.as_str())
                            .unwrap_or("unknown");

                        // Parse start and end dates
                        let (start_date, end_date) = if let Some(parts) = period.split(" to ").collect::<Vec<&str>>().get(0..2) {
                            (parts[0].to_string(), parts[1].to_string())
                        } else {
                            ("unknown".to_string(), "unknown".to_string())
                        };

                        create_simulation_result(
                            timesteps,
                            start_date,
                            end_date,
                            vec!["timeseries_data".to_string(), "summary_statistics".to_string()],
                            output_names
                        )
                    } else {
                        command_result.clone()
                    }
                }
                _ => command_result.clone()
            };

            let result_msg = create_result_message(
                session.id.clone(),
                command.clone(),
                execution_time_ms,
                true,
                result_data
            );
            transport.send_message(&result_msg)?;
        }
        Err(ref command_error) => {
            let error_msg = create_error_message(
                session.id.clone(),
                Some(command.clone()),
                format!("Command execution error: {}", command_error)
            );
            transport.send_message(&error_msg)?;
        }
    }

    // Check if command was interrupted
    if session.interrupt_flag.load(std::sync::atomic::Ordering::Relaxed) {
        let stopped_msg = create_stopped_message(
            session.id.clone(),
            command.clone(),
            execution_time_ms
        );
        transport.send_message(&stopped_msg)?;
    }

    // Reset to ready state
    session.set_ready()?;

    // Send ready message with appropriate return code
    let return_code = match &result {
        Ok(_) => if session.interrupt_flag.load(std::sync::atomic::Ordering::Relaxed) { 2 } else { 0 }, // 2 = interrupted
        Err(_) => 1, // 1 = error
    };
    let ready_msg = create_ready_message(session.id.clone(), return_code);
    transport.send_message(&ready_msg)?;

    Ok(())
}

fn handle_query_message(
    session: &Session,
    transport: &Transport,
    query_type: String
) -> Result<(), StdioError> {
    let result: Result<serde_json::Value, String> = match query_type.as_str() {
        "get_state" => {
            Ok(serde_json::to_value(session.get_state_info()).unwrap())
        }
        "get_session_id" => {
            Ok(serde_json::json!({"session_id": session.id}))
        }
        _ => {
            return send_error_message(session, transport, None, format!("Unknown query type: {}", query_type));
        }
    };

    match result {
        Ok(data) => {
            let result_msg = create_result_message(
                session.id.clone(),
                format!("query_{}", query_type),
                0.0, // Queries are instantaneous
                true,
                data
            );
            transport.send_message(&result_msg)?;
        }
        Err(e) => {
            send_error_message(session, transport, Some(format!("query_{}", query_type)), e.to_string())?;
        }
    }

    Ok(())
}

fn send_error_message(
    session: &Session,
    transport: &Transport,
    command: Option<String>,
    message: String
) -> Result<(), StdioError> {
    let error_msg = create_error_message(session.id.clone(), command, message);
    transport.send_message(&error_msg)?;
    Ok(())
}

// Progress reporting helper for commands
pub fn send_progress_update(
    session: &Session,
    transport: &Transport,
    current: i64,
    total: i64,
    task_type: &str
) -> Result<(), StdioError> {
    let progress_msg = create_progress_message(
        session.id.clone(),
        current,
        total,
        task_type.to_string()
    );
    transport.send_message(&progress_msg)?;
    Ok(())
}

#[derive(Debug, thiserror::Error)]
pub enum StdioError {
    #[error("Transport error: {0}")]
    Transport(#[from] TransportError),

    #[error("Session error: {0}")]
    Session(#[from] SessionError),

    #[error("Command error: {0}")]
    Command(#[from] CommandError),

    #[error("Unknown command: {0}")]
    UnknownCommand(String),

    #[error("Invalid message type: {0}")]
    InvalidMessageType(String),

    #[error("Invalid message data: {0}")]
    InvalidMessageData(String),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_message_handling() {
        // Test that we can parse command messages
        let fields = serde_json::json!({
            "c": "run_simulation",
            "p": {}
        });
        let msg = Message::new(MSG_COMMAND, None, fields);

        let (command, params) = extract_command_info(&msg).unwrap();
        assert_eq!(command, "run_simulation");
        assert!(params.is_object());
    }

    #[test]
    fn test_query_handling() {
        let fields = serde_json::json!({
            "q": "get_state"
        });
        let msg = Message::new(MSG_QUERY, None, fields);

        let query_type = extract_query_type(&msg).unwrap();
        assert_eq!(query_type, "get_state");
    }
}