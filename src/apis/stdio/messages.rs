use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    #[serde(rename = "type")]
    pub msg_type: String,
    pub timestamp: DateTime<Utc>,
    #[serde(default)]
    pub session_id: String,
    pub data: serde_json::Value,
}

impl Message {
    pub fn new(msg_type: &str, session_id: String, data: serde_json::Value) -> Self {
        Self {
            msg_type: msg_type.to_string(),
            timestamp: Utc::now(),
            session_id,
            data,
        }
    }
}

// Outgoing message types (kalixcli → frontend)
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum OutgoingMessageType {
    Ready,
    Busy,
    Progress,
    Result,
    Stopped,
    Error,
    Log,
}

// Incoming message types (frontend → kalixcli)  
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum IncomingMessageType {
    Command,
    Stop,
    Query,
    Terminate,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParameterSpec {
    pub name: String,
    #[serde(rename = "type")]
    pub param_type: String,
    pub required: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub default: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CommandSpec {
    pub name: String,
    pub description: String,
    pub parameters: Vec<ParameterSpec>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StateInfo {
    pub model_loaded: bool,
    pub data_loaded: bool,
    pub last_simulation: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReadyData {
    pub status: String,
    pub available_commands: Vec<CommandSpec>,
    pub current_state: StateInfo,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BusyData {
    pub status: String,
    pub executing_command: String,
    pub interruptible: bool,
    pub started_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProgressInfo {
    pub percent_complete: f64,
    pub current_step: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub estimated_remaining: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub details: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProgressData {
    pub command: String,
    pub progress: ProgressInfo,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CommandData {
    pub command: String,
    pub parameters: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StopData {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResultData {
    pub command: String,
    pub status: String,
    pub execution_time: String,
    pub result: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StoppedData {
    pub command: String,
    pub status: String,
    pub execution_time: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub partial_result: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorInfo {
    pub code: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub details: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorData {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub command: Option<String>,
    pub error: ErrorInfo,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueryData {
    pub query_type: String,
    pub parameters: serde_json::Value,
}

// Helper functions for creating common messages
pub fn create_ready_message(session_id: String, commands: Vec<CommandSpec>, state: StateInfo) -> Message {
    let data = ReadyData {
        status: "ready".to_string(),
        available_commands: commands,
        current_state: state,
    };
    Message::new("ready", session_id, serde_json::to_value(data).unwrap())
}

pub fn create_busy_message(session_id: String, command: String, interruptible: bool) -> Message {
    let data = BusyData {
        status: "busy".to_string(),
        executing_command: command,
        interruptible,
        started_at: Utc::now(),
    };
    Message::new("busy", session_id, serde_json::to_value(data).unwrap())
}

pub fn create_progress_message(session_id: String, command: String, progress: ProgressInfo) -> Message {
    let data = ProgressData { command, progress };
    Message::new("progress", session_id, serde_json::to_value(data).unwrap())
}

pub fn create_result_message(session_id: String, command: String, execution_time: String, result: serde_json::Value) -> Message {
    let data = ResultData {
        command,
        status: "success".to_string(),
        execution_time,
        result,
    };
    Message::new("result", session_id, serde_json::to_value(data).unwrap())
}

pub fn create_error_message(session_id: String, command: Option<String>, code: String, message: String, details: Option<serde_json::Value>) -> Message {
    let data = ErrorData {
        command,
        error: ErrorInfo { code, message, details },
    };
    Message::new("error", session_id, serde_json::to_value(data).unwrap())
}

pub fn create_stopped_message(session_id: String, command: String, execution_time: String, partial_result: Option<serde_json::Value>) -> Message {
    let data = StoppedData {
        command,
        status: "stopped".to_string(),
        execution_time,
        partial_result,
    };
    Message::new("stopped", session_id, serde_json::to_value(data).unwrap())
}