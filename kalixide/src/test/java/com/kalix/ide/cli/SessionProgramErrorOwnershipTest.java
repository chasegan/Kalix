package com.kalix.ide.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kalix.ide.utils.StatusReporter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A program driving a run claims only the errors that name commands it has sent. A
 * get_result rejected while the engine is busy with the run is the fetch's failure,
 * not the run's (ADR-0014 §3).
 */
class SessionProgramErrorOwnershipTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A session manager whose sends succeed without a process, recording what was sent. */
    private static final class StubSessionManager extends SessionManager {
        final List<String> sent = new ArrayList<>();

        StubSessionManager() {
            super(new ProcessExecutor(), StatusReporter.of(s -> { }, s -> { }), null);
        }

        @Override
        public CompletableFuture<Void> sendCommand(String sessionKey, String command) {
            sent.add(command);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static JsonMessage.SystemMessage parse(String json) throws Exception {
        return MAPPER.readValue(json, JsonMessage.SystemMessage.class);
    }

    /** Drives a RunModelProgram to the point where run_simulation has been sent. */
    private static RunModelProgram runningProgram(StubSessionManager sessions) throws Exception {
        RunModelProgram program = new RunModelProgram("s1", sessions,
            StatusReporter.of(s -> { }, s -> { }), null);
        program.start("[model]");
        program.onSessionReady();
        program.handleMessage(parse("{\"m\":\"res\",\"cmd\":\"load_model_string\",\"ok\":true,\"r\":{}}"));
        program.handleMessage(parse("{\"m\":\"rdy\",\"rc\":0}"));
        assertEquals(2, sessions.sent.size(), "load_model_string then run_simulation");
        assertTrue(sessions.sent.get(1).contains("\"run_simulation\""));
        return program;
    }

    @Test
    void fetchRejectedDuringTheRunDoesNotFailTheRun() throws Exception {
        RunModelProgram program = runningProgram(new StubSessionManager());

        boolean handled = program.handleMessage(parse("{\"m\":\"err\",\"cmd\":\"get_result\","
            + "\"p\":{\"series_name\":\"node.x.dsflow\"},\"msg\":\"Cannot process 'cmd' message while busy\"}"));

        assertFalse(handled, "an err naming another command is not the program's");
        assertTrue(program.isActive());
        assertFalse(program.isFailed());
    }

    @Test
    void theRunsOwnErrorFailsTheRun() throws Exception {
        RunModelProgram program = runningProgram(new StubSessionManager());

        boolean handled = program.handleMessage(parse("{\"m\":\"err\",\"cmd\":\"run_simulation\","
            + "\"p\":{},\"msg\":\"Command execution error: Model not loaded\"}"));

        assertTrue(handled);
        assertTrue(program.isFailed());
    }

    @Test
    void stopLandingWhileTheEngineIsIdleDoesNotFailTheRun() throws Exception {
        // The engine answers a stp it reads while idle with an err naming no command.
        RunModelProgram program = runningProgram(new StubSessionManager());

        boolean handled = program.handleMessage(parse("{\"m\":\"err\",\"msg\":\"No task is running\"}"));

        assertFalse(handled);
        assertTrue(program.isActive());
    }
}
