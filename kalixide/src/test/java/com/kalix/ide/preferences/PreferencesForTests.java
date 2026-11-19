package com.kalix.ide.preferences;

import java.io.File;

/** Opens the package-private preference test hook to tests in other packages. */
public final class PreferencesForTests {

    private PreferencesForTests() {
    }

    /** Reads and writes preferences in {@code file} from now on, starting from its contents. */
    public static void redirect(File file) {
        PreferenceManager.redirectForTesting(file);
    }
}
