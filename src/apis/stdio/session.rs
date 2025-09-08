use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, Ordering};
use std::collections::HashMap;
use chrono::{DateTime, Utc};
use crate::model::Model;
use crate::apis::stdio::messages::StateInfo;

#[derive(Debug, Clone)]
pub enum SessionState {
    Ready,
    Busy { 
        command: String,
        interruptible: bool,
        started_at: DateTime<Utc>,
    },
}

pub struct Session {
    pub id: String,
    pub state: Arc<Mutex<SessionState>>,
    pub interrupt_flag: Arc<AtomicBool>,
    pub model: Option<Model>,
    pub results: HashMap<String, serde_json::Value>,
}

impl Session {
    pub fn new() -> Self {
        Self {
            id: Self::generate_session_id(),
            state: Arc::new(Mutex::new(SessionState::Ready)),
            interrupt_flag: Arc::new(AtomicBool::new(false)),
            model: None,
            results: HashMap::new(),
        }
    }

    fn generate_session_id() -> String {
        let now = Utc::now();
        let random_suffix = uuid::Uuid::new_v4().to_string()[0..4].to_string();
        format!("sess_{}_{}", now.format("%Y%m%d_%H%M%S"), random_suffix)
    }

    pub fn is_ready(&self) -> bool {
        matches!(*self.state.lock().unwrap(), SessionState::Ready)
    }

    pub fn is_busy(&self) -> bool {
        matches!(*self.state.lock().unwrap(), SessionState::Busy { .. })
    }

    pub fn set_busy(&self, command: String, interruptible: bool) -> Result<(), SessionError> {
        let mut state = self.state.lock().unwrap();
        match *state {
            SessionState::Ready => {
                *state = SessionState::Busy {
                    command,
                    interruptible,
                    started_at: Utc::now(),
                };
                // Clear any previous interrupt flag
                self.interrupt_flag.store(false, Ordering::Relaxed);
                Ok(())
            }
            SessionState::Busy { .. } => {
                Err(SessionError::InvalidStateTransition("Already busy".to_string()))
            }
        }
    }

    pub fn set_ready(&self) -> Result<(), SessionError> {
        let mut state = self.state.lock().unwrap();
        *state = SessionState::Ready;
        // Clear interrupt flag when returning to ready
        self.interrupt_flag.store(false, Ordering::Relaxed);
        Ok(())
    }

    pub fn get_current_command(&self) -> Option<String> {
        let state = self.state.lock().unwrap();
        match &*state {
            SessionState::Busy { command, .. } => Some(command.clone()),
            SessionState::Ready => None,
        }
    }

    pub fn check_interrupt(&self) -> bool {
        self.interrupt_flag.load(Ordering::Relaxed)
    }

    pub fn request_interrupt(&self) -> Result<(), SessionError> {
        let state = self.state.lock().unwrap();
        match &*state {
            SessionState::Busy { interruptible: true, .. } => {
                self.interrupt_flag.store(true, Ordering::Relaxed);
                Ok(())
            }
            SessionState::Busy { interruptible: false, .. } => {
                Err(SessionError::NonInterruptibleTask)
            }
            SessionState::Ready => {
                Err(SessionError::InvalidStateTransition("No task running".to_string()))
            }
        }
    }

    pub fn get_state_info(&self) -> StateInfo {
        StateInfo {
            model_loaded: self.model.is_some(),
            data_loaded: self.model.as_ref()
                .map(|m| !m.inputs.is_empty())
                .unwrap_or(false),
            last_simulation: self.results.get("last_simulation")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string()),
        }
    }

    pub fn store_result(&mut self, key: String, value: serde_json::Value) {
        self.results.insert(key, value);
    }

    pub fn get_result(&self, key: &str) -> Option<&serde_json::Value> {
        self.results.get(key)
    }

    pub fn clear_results(&mut self) {
        self.results.clear();
    }

    pub fn set_model(&mut self, model: Model) {
        self.model = Some(model);
    }

    pub fn get_model_mut(&mut self) -> Option<&mut Model> {
        self.model.as_mut()
    }

    pub fn get_model(&self) -> Option<&Model> {
        self.model.as_ref()
    }

    pub fn clear_model(&mut self) {
        self.model = None;
    }
}

#[derive(Debug, thiserror::Error)]
pub enum SessionError {
    #[error("Invalid state transition: {0}")]
    InvalidStateTransition(String),
    
    #[error("Task is not interruptible")]
    NonInterruptibleTask,
    
    #[error("Session lock error")]
    LockError,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_session_creation() {
        let session = Session::new();
        assert!(session.is_ready());
        assert!(!session.is_busy());
        assert!(session.id.starts_with("sess_"));
    }

    #[test]
    fn test_state_transitions() {
        let session = Session::new();
        
        // Ready -> Busy
        session.set_busy("test_command".to_string(), true).unwrap();
        assert!(session.is_busy());
        assert_eq!(session.get_current_command().unwrap(), "test_command");
        
        // Busy -> Ready
        session.set_ready().unwrap();
        assert!(session.is_ready());
        assert!(session.get_current_command().is_none());
    }

    #[test]
    fn test_interrupt_handling() {
        let session = Session::new();
        
        // Set busy with interruptible task
        session.set_busy("long_task".to_string(), true).unwrap();
        
        // Request interrupt
        session.request_interrupt().unwrap();
        assert!(session.check_interrupt());
        
        // Return to ready state clears interrupt
        session.set_ready().unwrap();
        assert!(!session.check_interrupt());
    }
}