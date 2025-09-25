use std::io::{BufRead, BufReader, Write, BufWriter};
use std::sync::mpsc::{channel, Receiver, TryRecvError};
use std::sync::{Arc, Mutex};
use crate::apis::stdio::messages::Message;

pub struct Transport {
    stdin_rx: Receiver<String>,
    pub stdout: Arc<Mutex<BufWriter<Box<dyn Write + Send>>>>,
}

impl Transport {
    pub fn new() -> Self {
        let (stdin_tx, stdin_rx) = channel();
        
        // Spawn thread to read STDIN line by line
        std::thread::spawn(move || {
            let stdin = std::io::stdin();
            let reader = BufReader::new(stdin);
            for line in reader.lines() {
                match line {
                    Ok(line) => {
                        if stdin_tx.send(line).is_err() {
                            // Receiver dropped, exit thread
                            break;
                        }
                    }
                    Err(_) => {
                        // STDIN closed, exit thread
                        break;
                    }
                }
            }
        });

        Self {
            stdin_rx,
            stdout: Arc::new(Mutex::new(BufWriter::new(Box::new(std::io::stdout())))),
        }
    }

    pub fn send_message(&self, msg: &Message) -> Result<(), TransportError> {
        let json = serde_json::to_string(msg)
            .map_err(|e| TransportError::SerializationError(e.to_string()))?;
        
        let mut stdout = self.stdout.lock()
            .map_err(|_| TransportError::LockError("Failed to acquire stdout lock".to_string()))?;
        
        writeln!(stdout, "{}", json)
            .map_err(|e| TransportError::WriteError(e.to_string()))?;
        
        stdout.flush()
            .map_err(|e| TransportError::WriteError(e.to_string()))?;
        
        Ok(())
    }

    pub fn try_receive_message(&self) -> Result<Option<Message>, TransportError> {
        match self.stdin_rx.try_recv() {
            Ok(line) => {
                let msg = serde_json::from_str(&line)
                    .map_err(|e| TransportError::DeserializationError(e.to_string()))?;
                Ok(Some(msg))
            }
            Err(TryRecvError::Empty) => Ok(None),
            Err(TryRecvError::Disconnected) => Err(TransportError::StdinClosed),
        }
    }

    pub fn receive_message_blocking(&self) -> Result<Message, TransportError> {
        let line = self.stdin_rx.recv()
            .map_err(|_| TransportError::StdinClosed)?;
        
        let msg = serde_json::from_str(&line)
            .map_err(|e| TransportError::DeserializationError(e.to_string()))?;
        
        Ok(msg)
    }
}

#[derive(Debug, thiserror::Error)]
pub enum TransportError {
    #[error("Serialization error: {0}")]
    SerializationError(String),
    
    #[error("Deserialization error: {0}")]
    DeserializationError(String),
    
    #[error("Write error: {0}")]
    WriteError(String),
    
    #[error("Lock error: {0}")]
    LockError(String),
    
    #[error("STDIN closed")]
    StdinClosed,
}

#[cfg(test)]
mod tests {
    use crate::apis::stdio::messages::*;
    
    #[test]
    fn test_message_serialization() {
        let msg = Message::new(
            "test",
            "kalixcli_123".to_string(),
            serde_json::json!({"key": "value"})
        );
        
        let json = serde_json::to_string(&msg).unwrap();
        let deserialized: Message = serde_json::from_str(&json).unwrap();
        
        assert_eq!(msg.msg_type, deserialized.msg_type);
        assert_eq!(msg.kalixcli_uid, deserialized.kalixcli_uid);
    }
    
    #[test]
    fn test_json_contains_kalixcli_uid() {
        let msg = Message::new(
            "ready",
            "test_uid_456".to_string(),
            serde_json::json!({"status": "ready"})
        );
        
        let json = serde_json::to_string(&msg).unwrap();
        
        // Verify the JSON contains kalixcli_uid field, not session_id
        assert!(json.contains("kalixcli_uid"));
        assert!(!json.contains("session_id"));
        assert!(json.contains("test_uid_456"));
    }
}