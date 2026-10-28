package com.kalix.ide.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A program driving a run claims only the errors that name its own commands. A
 * get_result rejected while the engine is busy with the run is the fetch's failure,
 * not the run's (ADR-0014 §3).
 */
class SessionProgramErrorOwnershipTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonMessage.SystemMessage parse(String json) throws Exception {
        return mapper.readValue(json, JsonMessage.SystemMessage.class);
    }

    @Test
    void errorNamingAnotherCommandIsNotTheProgramsToHandle() throws Exception {
        JsonMessage.SystemMessage busyFetch = parse("{\"m\":\"err\",\"cmd\":\"get_result\","
            + "\"p\":{\"series_name\":\"node.x.dsflow\"},\"msg\":\"Cannot process 'cmd' message while busy\"}");

        assertTrue(AbstractSessionProgram.answersAnotherCommand(busyFetch, "load_model_string", "run_simulation"));
    }

    @Test
    void errorNamingAnOwnCommandIsTheProgramsToHandle() throws Exception {
        JsonMessage.SystemMessage runFailed = parse("{\"m\":\"err\",\"cmd\":\"run_simulation\","
            + "\"p\":{},\"msg\":\"Command execution error: Model not loaded\"}");

        assertFalse(AbstractSessionProgram.answersAnotherCommand(runFailed, "load_model_string", "run_simulation"));
    }

    @Test
    void errorNamingNoCommandStaysWithTheProgram() throws Exception {
        JsonMessage.SystemMessage malformed = parse("{\"m\":\"err\",\"msg\":\"Invalid message: EOF while parsing\"}");

        assertFalse(AbstractSessionProgram.answersAnotherCommand(malformed, "run_simulation"));
    }

    @Test
    void onlyErrorsAreScreened() throws Exception {
        JsonMessage.SystemMessage result = parse("{\"m\":\"res\",\"cmd\":\"get_result\",\"ok\":true,\"r\":{}}");

        assertFalse(AbstractSessionProgram.answersAnotherCommand(result, "run_simulation"));
    }
}
