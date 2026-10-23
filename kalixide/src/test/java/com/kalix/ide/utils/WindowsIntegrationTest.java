package com.kalix.ide.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs only on Windows, where the CI matrix exercises the real shell32 call: binding and
 * invoking SetCurrentProcessExplicitAppUserModelID through the FFM API returns S_OK.
 */
class WindowsIntegrationTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void setsTheAppUserModelId() throws Throwable {
        assertEquals(0, WindowsIntegration.setAppUserModelId(WindowsIntegration.APP_USER_MODEL_ID));
    }
}
