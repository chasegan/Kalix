---
title: "ADR-0014: Write whole lines to the engine, and match its replies by content"
---

# ADR-0014: Write whole lines to the engine, and match its replies by content

- **Status**: Proposed
- **Date**: 2026-10-28 (§2 decided 2026-07-07, recorded here after the fact)
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: cli, ide

## Context and problem statement

The IDE drives the engine over stdin and stdout in line-delimited JSON
([spec](https://github.com/chasegan/Kalix/blob/main/kalixide/docs/kalixcli-stdio-spec.md)).
Sends come from several IDE threads at once, and the IDE does not wait for
the engine's `rdy` before sending the next command: a plot refresh fires a
burst of `get_result` requests within a millisecond.

Three defects came from that traffic. In July 2026 the engine ran every
command on a worker thread, so the second `get_result` of a burst arrived
while the first still owned the session and was rejected as busy. In October
2026 the IDE wrote each line as three calls, text, newline, flush, so two
senders interleaved as `{A}{B}\n\n`: the engine rejected both halves and the
requests behind them were lost until a 60 second timeout. And the engine's
`err` for a failed command carried only the command name and a message, so
the IDE could not tell which request had failed and waited out the same
timeout.

The question is who serialises the traffic, and how each side identifies
what it is answering.

## Decision drivers

- Manifesto §2.6 — clarity off the hot path: a race-free IDE built from the
  idiomatic pieces, not a scheduler of its own.
- Manifesto §2.8 — less is more: no thread, queue or correlation layer the
  protocol can do without.

## Considered options

1. **Atomic line writes at the pipe, and replies that name their request.**
2. **A writer thread per session in the IDE**, serialising sends through a
   queue.
3. **Lockstep**: wait for `rdy` before sending the next command.
4. **A request id on every `cmd`**, echoed on every reply.

## Decision

Chosen option: **1**. The pipe is the only shared resource, so the lock is at
the pipe; the engine already queues and orders what it reads; and every reply
can say what it is for.

1. **One message is one write.** The whole line, `\n` included, goes to the
   writer in one call, under one lock. The protocol is line-framed, and a
   frame written in parts is not a frame.
2. **The IDE sends ahead; the engine's stdin queue is the serialisation
   point.** The engine reads stdin into a queue and runs one command at a
   time. Commands that cannot be interrupted (`get_result`, `load_model_*`)
   run inline on the session thread, so a burst drains in order; those that
   can (`run_simulation`, optimisation) run on a worker so `stp`, `query`
   and `term` are serviced meanwhile. A command arriving while a worker owns
   the session is not queued but rejected, with an `err` that names it.
3. **Replies are matched by content, never by position.** A `res` names its
   command, and a `get_result` result names its series (`r.series_name`).
   An `err` that answers a command carries `cmd` and the request's
   parameters as `p`, whether the command failed, was unknown, or was
   rejected while busy. An error that answers no command (a malformed line
   or a bad query, for instance) carries neither. Send order across IDE
   threads is not guaranteed, so nothing else could identify the request.
   The rule binds the receiver too: a program driving a run on a session
   claims only the errors that name commands it has sent.

Option 2 adds a thread per session with its own lifecycle, for a FIFO order
no caller depends on. Option 3 turns a burst of *n* fetches into *n* round
trips and makes every sender track the engine's state. Option 4 is the
textbook answer, but it touches every message on both sides to fix a gap
that only `err` had; `res` already names its content.

### Consequences

- ✅ The write fix is one method; nothing new to start, stop or order at
  shutdown.
- ✅ A failed fetch fails at once against an engine with the echo. The 60
  second timeout remains as the backstop for an engine that never answers,
  or an older one that answers without `p`.
- ❌ `p` echoes the request in full, so a model that fails to load comes
  back in its own `err`. Accepted: it is bounded by what the sender just
  wrote, and one rule with no exceptions beats a per-command one.
- ❌ Replies from a burst may not arrive in the order the IDE's threads
  sent. Accepted: §3 is what makes that safe, and the session log shows
  the engine's order, which is the one that happened.
- ❌ Content identifies the series, not the request: two requests for one
  series share one future, and a reply arriving after its request timed
  out is taken by a retry under the same key. Accepted: today's fetches
  are idempotent and the backstop is a minute. If a reply ever has to be
  told from a retry, that is option 4, in its own ADR.

## Worked example

Before, from a session log: two `get_result` lines glued and two empty ones.

```text
err  Invalid message: trailing characters at line 1 column 131
err  Invalid message: EOF while parsing a value at line 1 column 0
```

The IDE learned nothing until the timeout fired 60 seconds later, and the
series stayed off the plot until it was asked for again. After, a line is
whole, and a genuine failure names what it is for:

```json
{"m":"err","uid":"…","cmd":"get_result","p":{"series_name":"node.x.dsflow","format":"pixie"},"msg":"Command execution error: Result not found: Timeseries 'node.x.dsflow' not found in model results"}
```

The IDE fails the pending future for `uid:node.x.dsflow` at once.

## Enforcement

- **Structural**: §1 — `ProcessExecutor.RunningProcess.sendInput` is
  `synchronized` and writes one string; `RunningProcessSendInputTest` fires
  concurrent sends and asserts every line arrives whole. §2 — the
  inline-or-worker split is pinned by `non_interruptible_command_runs_inline`
  and `interruptible_command_runs_on_worker` in `src/apis/stdio/handlers.rs`.
  §3 — `create_error_message` in `src/apis/stdio/messages.rs` takes the
  request it answers, with its own tests; `JsonMessageErrorReplyTest` pins
  the IDE's reading of `p`; `AbstractSessionProgram` records every command
  a program sends and declines an `err` naming any other, pinned by
  `SessionProgramErrorOwnershipTest`.
- **Advisory**: that the session loop's busy branch echoes the command it
  rejects (the loop reads real stdin, so this is checked by hand against
  the binary, not by a unit test); that `sendInput` stays the only writer
  to the engine's stdin (`getProcess()` exposes the raw stream, and nothing
  uses it); that no caller comes to depend on send order.

## Links and references

- Spec: `kalixide/docs/kalixcli-stdio-spec.md` §4.8, §6.2 and §7.1.
- Commit `3d4391b8` (2026-07-07): non-interruptible commands run inline.
- Pull request: *pending*.

## Amendments

*None yet.*
