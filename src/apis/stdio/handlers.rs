use std::any::Any;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;
use std::sync::mpsc::{channel, Receiver, TryRecvError};
use std::time::{Duration, Instant};
use std::io::Write;
use crate::apis::stdio::session::{Session, SessionControl, SessionError};
use crate::apis::stdio::transport::{Transport, TransportError};
use crate::apis::stdio::commands::{Command, CommandRegistry, CommandError};
use crate::apis::stdio::messages::*;

/// How long the busy loop waits on stdin before re-checking whether the
/// running command has completed. Small enough that completion is reported
/// promptly; large enough not to spin.
const BUSY_POLL_INTERVAL: Duration = Duration::from_millis(20);

/// Everything the worker thread sends back when a command finishes.
/// The session travels with it: ownership returns to the session loop.
struct CommandCompletion {
    session: Session,
    command: String,
    result: Result<serde_json::Value, CommandError>,
    execution_time_ms: f64,
}

/// Handles the session loop keeps while the `Session` itself is owned by a
/// worker thread executing a command.
struct BusyContext {
    done_rx: Receiver<CommandCompletion>,
    control: SessionControl,
    /// Snapshot taken when the command started, used to answer `get_state`
    /// queries while the session is on the worker thread. The state cannot
    /// change until the command completes, so the snapshot stays accurate.
    state_snapshot: StateInfo,
}

/// Run the STDIO session loop.
///
/// Commands execute on a worker thread while this loop keeps reading stdin,
/// so `stp` (interrupt), `query`, and `term` messages are serviced *during*
/// long-running commands — that is the whole point of the interruptible
/// protocol. The `Session` (model included) moves to the worker for the
/// duration of the command and is handed back on completion.
pub fn run_stdio_session() -> Result<(), StdioError> {
    let transport = Transport::new();
    let registry = CommandRegistry::new();
    let session = Session::new();
    let session_id = session.id.clone();

    // Send initial ready message with return code 0 (success)
    transport.send_message(&create_ready_message(session_id.clone(), 0))?;

    // The session alternates between being held here (ready) and being owned
    // by a worker thread (busy). Exactly one of `idle` / `busy` is Some.
    let mut idle: Option<Session> = Some(session);
    let mut busy: Option<BusyContext> = None;

    loop {
        if let Some(ctx) = &busy {
            // 1) Has the running command finished?
            match ctx.done_rx.try_recv() {
                Ok(completion) => {
                    idle = Some(finish_command(&transport, completion)?);
                    busy = None;
                    continue;
                }
                Err(TryRecvError::Empty) => {}
                Err(TryRecvError::Disconnected) => {
                    // Unreachable in practice: the worker always sends, even on
                    // panic (catch_unwind). Treat as fatal rather than limp on
                    // without a session.
                    return Err(StdioError::WorkerLost);
                }
            }

            // 2) Service the messages that are valid while busy.
            match transport.receive_message_timeout(BUSY_POLL_INTERVAL) {
                Ok(None) => {} // timeout: loop around and re-check completion
                Ok(Some(msg)) => match msg.m.as_str() {
                    MSG_STOPPED => {
                        if let Err(e) = ctx.control.request_interrupt() {
                            send_error_msg(&transport, &session_id, None,
                                format!("Cannot stop: {}", e))?;
                        }
                    }
                    MSG_QUERY => match extract_query_type(&msg) {
                        Some(query_type) => answer_query(&transport, &session_id,
                            &query_type, &ctx.state_snapshot)?,
                        None => send_error_msg(&transport, &session_id, None,
                            "Invalid query format".to_string())?,
                    },
                    MSG_TERMINATE => {
                        // Best-effort interrupt; process exit ends the worker.
                        let _ = ctx.control.request_interrupt();
                        return Ok(());
                    }
                    other => {
                        send_error_msg(&transport, &session_id, None,
                            format!("Cannot process '{}' message while busy", other))?;
                    }
                },
                Err(TransportError::StdinClosed) => {
                    // Frontend went away: don't keep computing for nobody.
                    let _ = ctx.control.request_interrupt();
                    return Ok(());
                }
                Err(TransportError::DeserializationError(e)) => {
                    send_error_msg(&transport, &session_id, None,
                        format!("Invalid message: {}", e))?;
                }
                Err(e) => return Err(e.into()),
            }
        } else {
            // Ready: block until the next message arrives.
            let msg = match transport.receive_message_blocking() {
                Ok(msg) => msg,
                Err(TransportError::StdinClosed) => return Ok(()),
                Err(TransportError::DeserializationError(e)) => {
                    send_error_msg(&transport, &session_id, None,
                        format!("Invalid message: {}", e))?;
                    continue;
                }
                Err(e) => return Err(e.into()),
            };

            match msg.m.as_str() {
                MSG_COMMAND => match extract_command_info(&msg) {
                    Some((command, params)) => {
                        let session = idle.take().expect("session is resident while ready");
                        match start_command(&transport, &registry, session, command, params)? {
                            StartOutcome::Ready(session) => idle = Some(session),
                            StartOutcome::Busy(ctx) => busy = Some(ctx),
                        }
                    }
                    None => send_error_msg(&transport, &session_id, None,
                        "Invalid command format".to_string())?,
                },
                MSG_QUERY => match extract_query_type(&msg) {
                    Some(query_type) => {
                        let snapshot = idle.as_ref()
                            .expect("session is resident while ready")
                            .get_state_info();
                        answer_query(&transport, &session_id, &query_type, &snapshot)?;
                    }
                    None => send_error_msg(&transport, &session_id, None,
                        "Invalid query format".to_string())?,
                },
                MSG_STOPPED => {
                    send_error_msg(&transport, &session_id, None,
                        "No task is running".to_string())?;
                }
                MSG_TERMINATE => return Ok(()),
                other => {
                    send_error_msg(&transport, &session_id, None,
                        format!("Unknown message type: {}", other))?;
                }
            }
        }
    }
}

enum StartOutcome {
    /// The command never started (e.g. unknown name); the session stays here.
    Ready(Session),
    /// The command is running on a worker thread that now owns the session.
    Busy(BusyContext),
}

/// Validate and launch a command.
///
/// Interruptible commands (simulation, optimisation) are handed to a worker
/// thread so the session loop can keep servicing `stp`/`query`/`term` while they
/// run; the returned `BusyContext` carries the completion channel and control
/// handles for that.
///
/// Non-interruptible commands (`get_result`, `get_state`, `load_model`, …) cannot
/// be stopped, so there is nothing to offload: they run *synchronously* on the
/// session-loop thread and the session never leaves it. This is deliberate. It
/// keeps the session resident between calls, so a burst of `get_result` fetches is
/// drained one at a time from the ready branch — rather than a second fetch
/// arriving while a worker still owns the session and being rejected by the
/// busy-branch `other` arm ("Cannot process 'cmd' message while busy"). The wire
/// sequence is identical either way (`bsy` → `res`/`err` → `rdy`); only the timing
/// and the absence of a worker differ.
fn start_command(
    transport: &Transport,
    registry: &CommandRegistry,
    session: Session,
    command: String,
    parameters: serde_json::Value,
) -> Result<StartOutcome, StdioError> {
    let session_id = session.id.clone();

    let command_spec = match registry.get_command(&command) {
        Some(spec) => spec,
        None => {
            send_error_msg(transport, &session_id, Some(command.clone()),
                format!("Unknown command: {}", command))?;
            return Ok(StartOutcome::Ready(session));
        }
    };

    let is_interruptible = command_spec.interruptible();
    transport.send_message(&create_busy_message(session_id.clone(), command.clone(), is_interruptible))?;

    if let Err(e) = session.set_busy(command.clone(), is_interruptible) {
        send_error_msg(transport, &session_id, Some(command),
            format!("Cannot start command: {}", e))?;
        return Ok(StartOutcome::Ready(session));
    }

    let progress_callback = make_progress_callback(transport, session_id);

    if !is_interruptible {
        // Synchronous path: run inline, report the result, hand the session back.
        // finish_command sends `res`/`err` + `rdy` and calls set_ready, so the
        // session is resident again before the loop reads the next message.
        let completion = execute_to_completion(command_spec, session, command, parameters, progress_callback);
        let session = finish_command(transport, completion)?;
        return Ok(StartOutcome::Ready(session));
    }

    // Interruptible path: hand the session to a worker for the command's duration.
    let control = session.control();
    let state_snapshot = session.get_state_info();
    let (done_tx, done_rx) = channel();
    std::thread::spawn(move || {
        // If the receiver is gone the session loop is already exiting.
        let _ = done_tx.send(execute_to_completion(
            command_spec, session, command, parameters, progress_callback));
    });

    Ok(StartOutcome::Busy(BusyContext { done_rx, control, state_snapshot }))
}

/// Run a command to completion, catching panics so a misbehaving command fails
/// its own request rather than tearing down the session loop. The session travels
/// through by value and returns inside the `CommandCompletion`, so ownership can
/// flow back to the loop whether the command ran inline or on a worker thread.
fn execute_to_completion(
    command_spec: Arc<dyn Command>,
    mut session: Session,
    command: String,
    parameters: serde_json::Value,
    progress_callback: Box<dyn Fn(ProgressInfo) + Send + Sync>,
) -> CommandCompletion {
    let start_time = Instant::now();
    let result = catch_unwind(AssertUnwindSafe(|| {
        command_spec.execute(&mut session, parameters, progress_callback)
    }))
    .unwrap_or_else(|panic_info| {
        Err(CommandError::ExecutionError(
            format!("Command panicked: {}", panic_message(&panic_info))
        ))
    });

    CommandCompletion {
        session,
        command,
        result,
        execution_time_ms: duration_to_ms(start_time.elapsed()),
    }
}

/// Build the progress callback a command uses to stream `prg` messages to the
/// shared, mutex-guarded stdout writer.
fn make_progress_callback(
    transport: &Transport,
    session_id: String,
) -> Box<dyn Fn(ProgressInfo) + Send + Sync> {
    let stdout = transport.stdout.clone();
    Box::new(move |progress: ProgressInfo| {
        // Use override values if provided, otherwise use percent-based progress
        let current = progress.current.unwrap_or(progress.percent_complete as i64);
        let total = progress.total.unwrap_or(100);
        let task_type = progress.task_type.unwrap_or_else(|| "sim".to_string());

        let progress_msg = create_progress_message(
            session_id.clone(),
            current,
            total,
            task_type,
            progress.data,
        );

        if let Ok(json) = serde_json::to_string(&progress_msg) {
            if let Ok(mut stdout) = stdout.lock() {
                let _ = writeln!(stdout, "{}", json);
                let _ = stdout.flush();
            }
        }
    })
}

/// Report a completed command per the protocol and return the session to the
/// ready state: `res` + `rdy(0)` on success, `stp` + `rdy(2)` when
/// interrupted, `err` + `rdy(1)` on any other failure.
fn finish_command(
    transport: &Transport,
    completion: CommandCompletion,
) -> Result<Session, StdioError> {
    let CommandCompletion { session, command, result, execution_time_ms } = completion;
    let session_id = session.id.clone();

    let return_code = match &result {
        Ok(command_result) => {
            let result_data = match command.as_str() {
                "run_simulation" => restructure_simulation_result(command_result),
                _ => command_result.clone(),
            };
            transport.send_message(&create_result_message(
                session_id.clone(), command.clone(), execution_time_ms, true, result_data))?;
            0
        }
        Err(CommandError::Interrupted) => {
            transport.send_message(&create_stopped_message(
                session_id.clone(), command.clone(), execution_time_ms))?;
            2
        }
        Err(command_error) => {
            transport.send_message(&create_error_message(
                session_id.clone(), Some(command.clone()),
                format!("Command execution error: {}", command_error)))?;
            1
        }
    };

    session.set_ready()?;
    transport.send_message(&create_ready_message(session_id, return_code))?;
    Ok(session)
}

/// Reshape a raw run_simulation result into the compact `ts` structure the
/// protocol documents for simulation results.
fn restructure_simulation_result(command_result: &serde_json::Value) -> serde_json::Value {
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
            output_names,
        )
    } else {
        command_result.clone()
    }
}

fn answer_query(
    transport: &Transport,
    session_id: &str,
    query_type: &str,
    state_info: &StateInfo,
) -> Result<(), StdioError> {
    let data = match query_type {
        "get_state" => serde_json::to_value(state_info).unwrap(),
        "get_session_id" => serde_json::json!({"session_id": session_id}),
        _ => {
            return send_error_msg(transport, session_id, None,
                format!("Unknown query type: {}", query_type));
        }
    };

    let result_msg = create_result_message(
        session_id.to_string(),
        format!("query_{}", query_type),
        0.0, // Queries are instantaneous
        true,
        data,
    );
    transport.send_message(&result_msg)?;
    Ok(())
}

fn send_error_msg(
    transport: &Transport,
    session_id: &str,
    command: Option<String>,
    message: String,
) -> Result<(), StdioError> {
    let error_msg = create_error_message(session_id.to_string(), command, message);
    transport.send_message(&error_msg)?;
    Ok(())
}

/// Extract a printable message from a caught panic payload.
fn panic_message(panic_info: &Box<dyn Any + Send>) -> String {
    if let Some(s) = panic_info.downcast_ref::<&str>() {
        s.to_string()
    } else if let Some(s) = panic_info.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic".to_string()
    }
}

#[derive(Debug, thiserror::Error)]
pub enum StdioError {
    #[error("Transport error: {0}")]
    Transport(#[from] TransportError),

    #[error("Session error: {0}")]
    Session(#[from] SessionError),

    #[error("Command error: {0}")]
    Command(#[from] CommandError),

    #[error("Command worker thread terminated without reporting a result")]
    WorkerLost,
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

    // Non-interruptible commands must execute synchronously in the session loop
    // (StartOutcome::Ready), not on a worker (StartOutcome::Busy). This is the
    // invariant that fixes the get_result busy-rejection bug: running inline keeps
    // the session resident, so a burst of get_result commands is drained one at a
    // time from the ready branch instead of a second fetch arriving while a worker
    // still owns the session and being rejected ("Cannot process 'cmd' while busy").
    #[test]
    fn non_interruptible_command_runs_inline() {
        let transport = Transport::new();
        let registry = CommandRegistry::new();
        // get_version is non-interruptible and needs no loaded model.
        let outcome = start_command(&transport, &registry, Session::new(),
            "get_version".to_string(), serde_json::json!({}))
            .expect("start_command should not fail");
        assert!(matches!(outcome, StartOutcome::Ready(_)),
            "non-interruptible command must run inline and return Ready");
    }

    // Interruptible commands must still go to a worker so the loop can service
    // stp/query/term while they run.
    #[test]
    fn interruptible_command_runs_on_worker() {
        let transport = Transport::new();
        let registry = CommandRegistry::new();
        // run_simulation is interruptible; with no model it fails fast on the
        // worker, but start_command returns Busy immediately regardless. Dropping
        // the returned BusyContext drops done_rx; the worker's send then no-ops.
        let outcome = start_command(&transport, &registry, Session::new(),
            "run_simulation".to_string(), serde_json::json!({}))
            .expect("start_command should not fail");
        assert!(matches!(outcome, StartOutcome::Busy(_)),
            "interruptible command must run on a worker and return Busy");
    }
}
