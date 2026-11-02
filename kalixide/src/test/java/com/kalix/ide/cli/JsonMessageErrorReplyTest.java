package com.kalix.ide.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * An {@code err} that answers a command echoes the command's parameters as {@code p}, and
 * that is how a failed get_result is tied back to the series it was for. The IDE sends
 * requests ahead and the engine answers in its own order, so nothing else identifies it.
 */
class JsonMessageErrorReplyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void errorReplyCarriesTheRequestParameters() throws Exception {
        String json = "{\"m\":\"err\",\"uid\":\"u1\",\"cmd\":\"get_result\","
            + "\"p\":{\"series_name\":\"node.x.dsflow\",\"format\":\"pixie\"},"
            + "\"msg\":\"Command execution error: Result not found\"}";

        JsonMessage.SystemMessage message = mapper.readValue(json, JsonMessage.SystemMessage.class);

        assertEquals(JsonStdioTypes.SystemMessageType.ERROR, message.systemMessageType());
        assertEquals("get_result", message.getCommand());
        assertEquals("node.x.dsflow", message.getParameters().path("series_name").asText());
        assertEquals("Command execution error: Result not found", message.getErrorMessage());
    }

    @Test
    void errorAnsweringNoCommandHasNoParameters() throws Exception {
        String json = "{\"m\":\"err\",\"uid\":\"u1\",\"msg\":\"Invalid message: EOF while parsing\"}";

        JsonMessage.SystemMessage message = mapper.readValue(json, JsonMessage.SystemMessage.class);

        assertNull(message.getCommand());
        assertNull(message.getParameters());
    }
}
