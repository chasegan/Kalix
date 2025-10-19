use serde::{Deserialize, Serialize};

// JSON Protocol - Single Message Structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    pub m: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uid: Option<String>,
    #[serde(flatten)]
    pub fields: serde_json::Value,
}

impl Message {
    pub fn new(message_type: &str, uid: Option<String>, fields: serde_json::Value) -> Self {
        Self {
            m: message_type.to_string(),
            uid,
            fields,
        }
    }
}

// Message type constants
pub const MSG_READY: &str = "rdy";
pub const MSG_BUSY: &str = "bsy";
pub const MSG_PROGRESS: &str = "prg";
pub const MSG_RESULT: &str = "res";
pub const MSG_ERROR: &str = "err";
pub const MSG_STOPPED: &str = "stp";
pub const MSG_COMMAND: &str = "cmd";
pub const MSG_QUERY: &str = "query";
pub const MSG_TERMINATE: &str = "term";

// Helper structs for specific data structures
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CommandSpec {
    pub name: String,
    pub description: String,
    pub parameters: Vec<ParameterSpec>,
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
pub struct StateInfo {
    pub model_loaded: bool,
    pub data_loaded: bool,
    pub last_simulation: Option<String>,
}

// Message creation functions
pub fn create_ready_message(kalixcli_uid: String, return_code: i32) -> Message {
    let fields = serde_json::json!({
        "rc": return_code
    });
    Message::new(MSG_READY, Some(kalixcli_uid), fields)
}

pub fn create_busy_message(kalixcli_uid: String, command: String, interruptible: bool) -> Message {
    let fields = serde_json::json!({
        "cmd": command,
        "int": interruptible
    });
    Message::new(MSG_BUSY, Some(kalixcli_uid), fields)
}

pub fn create_progress_message(kalixcli_uid: String, current: i64, total: i64, task_type: String, data: Option<Vec<f64>>) -> Message {
    let mut fields = serde_json::json!({
        "i": current,
        "n": total,
        "t": task_type
    });

    // Add optional data field if provided
    if let Some(d) = data {
        fields.as_object_mut().unwrap().insert("d".to_string(), serde_json::json!(d));
    }

    Message::new(MSG_PROGRESS, Some(kalixcli_uid), fields)
}

pub fn create_result_message(kalixcli_uid: String, command: String, exec_time_ms: f64, success: bool, result: serde_json::Value) -> Message {
    let fields = serde_json::json!({
        "cmd": command,
        "exec_ms": exec_time_ms,
        "ok": success,
        "r": result
    });
    Message::new(MSG_RESULT, Some(kalixcli_uid), fields)
}

pub fn create_error_message(kalixcli_uid: String, command: Option<String>, message: String) -> Message {
    let mut fields = serde_json::json!({
        "msg": message
    });
    if let Some(cmd) = command {
        fields.as_object_mut().unwrap().insert("cmd".to_string(), serde_json::Value::String(cmd));
    }
    Message::new(MSG_ERROR, Some(kalixcli_uid), fields)
}

pub fn create_stopped_message(kalixcli_uid: String, command: String, exec_time_ms: f64) -> Message {
    let fields = serde_json::json!({
        "cmd": command,
        "exec_ms": exec_time_ms
    });
    Message::new(MSG_STOPPED, Some(kalixcli_uid), fields)
}

// Helper functions for parsing incoming messages
pub fn extract_command_info(msg: &Message) -> Option<(String, serde_json::Value)> {
    if msg.m == MSG_COMMAND {
        if let (Some(command), Some(params)) = (
            msg.fields.get("c").and_then(|v| v.as_str()),
            msg.fields.get("p")
        ) {
            return Some((command.to_string(), params.clone()));
        }
    }
    None
}

pub fn extract_query_type(msg: &Message) -> Option<String> {
    if msg.m == MSG_QUERY {
        msg.fields.get("q").and_then(|v| v.as_str()).map(|s| s.to_string())
    } else {
        None
    }
}

pub fn extract_stop_reason(msg: &Message) -> Option<String> {
    if msg.m == MSG_STOPPED {
        msg.fields.get("reason").and_then(|v| v.as_str()).map(|s| s.to_string())
    } else {
        None
    }
}

// Helper for creating simulation result data structure
pub fn create_simulation_result(timesteps: i64, start_date: String, end_date: String, available_types: Vec<String>, outputs: Vec<String>) -> serde_json::Value {
    serde_json::json!({
        "ts": {
            "len": timesteps,
            "start": start_date,
            "end": end_date,
            "o": available_types,
            "outputs": outputs
        }
    })
}

// Helper for parsing execution time from duration
pub fn duration_to_ms(duration: std::time::Duration) -> f64 {
    duration.as_secs_f64() * 1000.0
}

// Progress information structure for commands module
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProgressInfo {
    pub percent_complete: f64,
    pub current_step: String,
    pub estimated_remaining: Option<String>,
    pub data: Option<Vec<f64>>,  // Optional numeric data (e.g., best objective for optimisation)

    // Optional override values for STDIO protocol (if not provided, uses percent_complete/100)
    pub current: Option<i64>,    // Current progress value (e.g., evaluations)
    pub total: Option<i64>,      // Total value (e.g., termination_evaluations)
    pub task_type: Option<String>, // Task type (defaults to "sim")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ready_message_creation() {
        let msg = create_ready_message("test_uid_123".to_string(), 0);
        assert_eq!(msg.m, "rdy");
        assert_eq!(msg.uid, Some("test_uid_123".to_string()));
        assert_eq!(msg.fields["rc"], 0);
    }

    #[test]
    fn test_progress_message_creation() {
        let msg = create_progress_message("test_uid_123".to_string(), 100, 1000, "sim".to_string(), None);
        assert_eq!(msg.m, "prg");
        assert_eq!(msg.fields["i"], 100);
        assert_eq!(msg.fields["n"], 1000);
        assert_eq!(msg.fields["t"], "sim");
        assert!(msg.fields.get("d").is_none());
    }

    #[test]
    fn test_progress_message_with_data() {
        let msg = create_progress_message("test_uid_123".to_string(), 100, 1000, "cal".to_string(), Some(vec![0.856]));
        assert_eq!(msg.m, "prg");
        assert_eq!(msg.fields["i"], 100);
        assert_eq!(msg.fields["n"], 1000);
        assert_eq!(msg.fields["t"], "cal");
        assert_eq!(msg.fields["d"][0], 0.856);
    }

    #[test]
    fn test_command_extraction() {
        let fields = serde_json::json!({
            "c": "run_simulation",
            "p": {"param1": "value1"}
        });
        let msg = Message::new("cmd", None, fields);

        let (command, params) = extract_command_info(&msg).unwrap();
        assert_eq!(command, "run_simulation");
        assert_eq!(params["param1"], "value1");
    }

    #[test]
    fn test_simulation_result_structure() {
        let result = create_simulation_result(
            48824,
            "1889-01-01".to_string(),
            "2022-09-04".to_string(),
            vec!["timeseries_data".to_string(), "summary_statistics".to_string()],
            vec!["node.output1".to_string(), "node.output2".to_string()]
        );

        assert_eq!(result["ts"]["len"], 48824);
        assert_eq!(result["ts"]["start"], "1889-01-01");
        assert_eq!(result["ts"]["outputs"].as_array().unwrap().len(), 2);
    }
}