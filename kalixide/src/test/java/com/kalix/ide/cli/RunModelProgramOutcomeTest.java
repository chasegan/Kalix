package com.kalix.ide.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kalix.ide.utils.StatusReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run outcome a {@link RunModelProgram} reports, for the paths that can be driven
 * without an engine process. Reaching a success or a stop needs the load and run
 * commands to be sent, so those two are not covered here.
 */
class RunModelProgramOutcomeTest {

    private final ProcessExecutor processExecutor = new ProcessExecutor();
    private final SessionManager sessionManager =
        new SessionManager(processExecutor, StatusReporter.statusOnly(status -> { }), null);
    private final List<RunModelProgram.Outcome> outcomes = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        processExecutor.shutdown();
    }

    private RunModelProgram program() {
        RunModelProgram program = new RunModelProgram("no-such-session", sessionManager,
            StatusReporter.statusOnly(status -> { }), null);
        program.start("[attributes]");
        program.setOnFinished(outcomes::add);
        return program;
    }

    private static JsonMessage.SystemMessage message(String type) throws Exception {
        return new ObjectMapper().readValue("{\"m\": \"" + type + "\"}", JsonMessage.SystemMessage.class);
    }

    @Test
    void engineErrorIsReportedAsFailed() throws Exception {
        RunModelProgram program = program();

        program.handleMessage(message("err"));

        assertEquals(List.of(RunModelProgram.Outcome.FAILED), outcomes);
        assertTrue(program.isFailed());
    }

    @Test
    void outcomeIsReportedOnce() throws Exception {
        RunModelProgram program = program();

        program.handleMessage(message("err"));
        program.handleMessage(message("err"));

        assertEquals(List.of(RunModelProgram.Outcome.FAILED), outcomes);
    }

    /** The load command cannot be sent to a session that does not exist. */
    @Test
    void failedSendIsReportedAsFailed() throws Exception {
        CompletableFuture<RunModelProgram.Outcome> reported = new CompletableFuture<>();
        RunModelProgram program = program();
        program.setOnFinished(reported::complete);

        program.handleMessage(message("rdy"));

        assertEquals(RunModelProgram.Outcome.FAILED, reported.get(5, TimeUnit.SECONDS));
    }
}
