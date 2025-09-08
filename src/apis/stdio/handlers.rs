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
    
    // Send initial ready message
    let ready_msg = create_ready_message(
        session.id.clone(),
        registry.get_all_specs(),
        session.get_state_info()
    );
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
                    "SESSION_ERROR".to_string(),
                    e.to_string(),
                    None
                );
                transport.send_message(&error_msg)?;
                
                // Ensure we're in ready state after error
                let _ = session.set_ready();
                
                // Send ready message to indicate recovery
                let ready_msg = create_ready_message(
                    session.id.clone(),
                    registry.get_all_specs(),
                    session.get_state_info()
                );
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
        
        // Frontend messages don't include session_id - no validation needed
        
        match msg.msg_type.as_str() {
            "command" => {
                handle_command_message(session, transport, registry, msg)?;
            }
            "query" => {
                handle_query_message(session, transport, msg)?;
            }
            "terminate" => {
                return Ok(false); // Signal to exit
            }
            _ => {
                return Err(StdioError::InvalidMessageType(msg.msg_type));
            }
        }
    } else if session.is_busy() {
        // Check for interrupt messages while busy
        if let Some(msg) = transport.try_receive_message()? {
            // Frontend messages don't include session_id - no validation needed
            
            match msg.msg_type.as_str() {
                "stop" => {
                    handle_stop_message(session, transport, msg)?;
                }
                "query" => {
                    handle_query_message(session, transport, msg)?;
                }
                "terminate" => {
                    // Force interrupt and exit
                    let _ = session.request_interrupt();
                    return Ok(false);
                }
                _ => {
                    // Invalid message while busy, send error but continue
                    let error_msg = create_error_message(
                        session.id.clone(),
                        None,
                        "INVALID_STATE".to_string(),
                        format!("Cannot process '{}' message while busy", msg.msg_type),
                        None
                    );
                    transport.send_message(&error_msg)?;
                }
            }
        }
        
        // Small sleep to avoid busy-waiting
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    
    Ok(true)
}

fn handle_command_message(
    session: &mut Session,
    transport: &Transport,
    registry: &CommandRegistry,
    msg: Message
) -> Result<(), StdioError> {
    let command_data: CommandData = serde_json::from_value(msg.data)
        .map_err(|e| StdioError::InvalidMessageData(e.to_string()))?;
    
    let command = registry.get_command(&command_data.command)
        .ok_or_else(|| StdioError::UnknownCommand(command_data.command.clone()))?;
    
    // Set session to busy
    session.set_busy(command_data.command.clone(), command.interruptible())?;
    
    // Send busy message
    let busy_msg = create_busy_message(
        session.id.clone(),
        command_data.command.clone(),
        command.interruptible()
    );
    transport.send_message(&busy_msg)?;
    
    let start_time = Instant::now();
    
    // Create progress callback with cloned transport data
    let session_id = session.id.clone();
    let command_name = command_data.command.clone();
    let stdout_handle = transport.stdout.clone();
    
    let progress_callback = Box::new(move |progress: ProgressInfo| {
        let progress_msg = create_progress_message(
            session_id.clone(),
            command_name.clone(),
            progress
        );
        
        if let Ok(json) = serde_json::to_string(&progress_msg) {
            if let Ok(mut stdout) = stdout_handle.lock() {
                let _ = writeln!(stdout, "{}", json);
                let _ = stdout.flush();
            }
        }
    });
    
    // Execute command
    let result = command.execute(session, command_data.parameters, progress_callback);
    
    let execution_time = format_duration(start_time.elapsed());
    
    // Handle result and send appropriate message
    match result {
        Ok(result_data) => {
            let result_msg = create_result_message(
                session.id.clone(),
                command_data.command,
                execution_time,
                result_data
            );
            transport.send_message(&result_msg)?;
        }
        Err(CommandError::Interrupted) => {
            let stopped_msg = create_stopped_message(
                session.id.clone(),
                command_data.command,
                execution_time,
                None // TODO: Could include partial results
            );
            transport.send_message(&stopped_msg)?;
        }
        Err(e) => {
            let error_msg = create_error_message(
                session.id.clone(),
                Some(command_data.command),
                "COMMAND_ERROR".to_string(),
                e.to_string(),
                None
            );
            transport.send_message(&error_msg)?;
        }
    }
    
    // Return to ready state
    session.set_ready()?;
    
    // Send ready message
    let registry_specs = registry.get_all_specs();
    let ready_msg = create_ready_message(
        session.id.clone(),
        registry_specs,
        session.get_state_info()
    );
    transport.send_message(&ready_msg)?;
    
    Ok(())
}

fn handle_stop_message(
    session: &mut Session,
    _transport: &Transport,
    _msg: Message
) -> Result<(), StdioError> {
    session.request_interrupt()?;
    Ok(())
}

fn handle_query_message(
    session: &mut Session,
    transport: &Transport,
    msg: Message
) -> Result<(), StdioError> {
    let query_data: QueryData = serde_json::from_value(msg.data)
        .map_err(|e| StdioError::InvalidMessageData(e.to_string()))?;
    
    let result = match query_data.query_type.as_str() {
        "get_state" => {
            Ok(serde_json::to_value(session.get_state_info()).unwrap())
        }
        "get_session_id" => {
            Ok(serde_json::json!({"session_id": session.id}))
        }
        _ => {
            Err(format!("Unknown query type: {}", query_data.query_type))
        }
    };
    
    match result {
        Ok(data) => {
            let result_msg = create_result_message(
                session.id.clone(),
                format!("query_{}", query_data.query_type),
                "00:00:00".to_string(),
                data
            );
            transport.send_message(&result_msg)?;
        }
        Err(error) => {
            let error_msg = create_error_message(
                session.id.clone(),
                Some(format!("query_{}", query_data.query_type)),
                "QUERY_ERROR".to_string(),
                error,
                None
            );
            transport.send_message(&error_msg)?;
        }
    }
    
    Ok(())
}

fn format_duration(duration: std::time::Duration) -> String {
    let total_seconds = duration.as_secs();
    let hours = total_seconds / 3600;
    let minutes = (total_seconds % 3600) / 60;
    let seconds = total_seconds % 60;
    format!("{:02}:{:02}:{:02}", hours, minutes, seconds)
}

#[derive(Debug, thiserror::Error)]
pub enum StdioError {
    #[error("Transport error: {0}")]
    Transport(#[from] TransportError),
    
    #[error("Session error: {0}")]
    Session(#[from] SessionError),
    
    #[error("Invalid message type: {0}")]
    InvalidMessageType(String),
    
    #[error("Invalid message data: {0}")]
    InvalidMessageData(String),
    
    #[error("Unknown command: {0}")]
    UnknownCommand(String),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_format_duration() {
        let duration = std::time::Duration::from_secs(3661); // 1h 1m 1s
        assert_eq!(format_duration(duration), "01:01:01");
        
        let duration = std::time::Duration::from_secs(125); // 2m 5s
        assert_eq!(format_duration(duration), "00:02:05");
    }
}