package com.kalix.ide.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kalix.ide.utils.StatusReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The parts of a mass balance report request that can be exercised without an engine
 * process: the command on the wire, and what a caller sees when the send fails.
 */
class MassBalanceReportRequestTest {

    private final ProcessExecutor processExecutor = new ProcessExecutor();
    private final SessionManager sessionManager = new SessionManager(processExecutor, StatusReporter.statusOnly(status -> { }), null);

    @AfterEach
    void tearDown() {
        processExecutor.shutdown();
    }

    /** The name must match the engine's registry (see test_command_registry in commands.rs). */
    @Test
    void commandNamesTheEnginesMassBalanceCommand() throws Exception {
        JsonNode message = new ObjectMapper().readTree(JsonStdioProtocol.Commands.getMassBalanceReport());

        assertEquals("cmd", message.get("m").asText());
        assertEquals("get_mass_balance_report", message.get("c").asText());
    }

    private static Throwable failureOf(CompletableFuture<String> request) {
        return assertThrows(ExecutionException.class, () -> request.get(5, TimeUnit.SECONDS)).getCause();
    }

    /** The cause reaches the caller unwrapped, so its message can be shown as it is. */
    @Test
    void sendFailureReachesTheCallerWithItsOwnMessage() {
        Throwable failure = failureOf(sessionManager.requestMassBalanceReport("no-such-session"));

        assertInstanceOf(IllegalArgumentException.class, failure);
        assertEquals("Session not found: no-such-session", failure.getMessage());
    }

    /** A failed request must not be handed to the next caller for the same session. */
    @Test
    void failedRequestIsNotReused() {
        CompletableFuture<String> first = sessionManager.requestMassBalanceReport("no-such-session");
        failureOf(first);

        CompletableFuture<String> second = sessionManager.requestMassBalanceReport("no-such-session");

        assertNotSame(first, second);
        failureOf(second);
    }
}
