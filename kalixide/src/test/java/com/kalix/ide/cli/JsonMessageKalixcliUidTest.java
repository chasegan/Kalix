package com.kalix.ide.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class JsonMessageKalixcliUidTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testCommandMessageSerialization() throws Exception {
        JsonMessage.CommandMessage message = new JsonMessage.CommandMessage();
        message.setMessageType("cmd");
        message.setSessionId("test_kalixcli_uid_123");
        
        String json = mapper.writeValueAsString(message);
        
        // Verify the JSON contains uid field for compact protocol
        assertTrue(json.contains("uid"), "JSON should contain uid field");
        assertTrue(json.contains("test_kalixcli_uid_123"), "JSON should contain the UID value");
        
        System.out.println("Command message JSON: " + json);
    }

    @Test
    void testSystemMessageDeserialization() throws Exception {
        String json = "{\n" +
                "  \"m\": \"rdy\",\n" +
                "  \"uid\": \"test_uid_456\",\n" +
                "  \"rc\": 0\n" +
                "}";

        JsonMessage.SystemMessage message = mapper.readValue(json, JsonMessage.SystemMessage.class);

        assertEquals("rdy", message.getMessageType());
        assertEquals("test_uid_456", message.getSessionId());

        System.out.println("Deserialized system message: " + message.toString());
    }
}