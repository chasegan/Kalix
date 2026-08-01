package com.kalix.ide.filedialog;

import java.io.File;
import java.util.Comparator;
import java.util.List;

/**
 * The save dialog's name/extension rules, as pure functions.
 *
 * <p>Kept separate from {@link KalixFileDialog} because they are the fiddly part: multi-part
 * suffixes like {@code .res.csv} make "swap the extension" more than "cut at the last dot",
 * and getting it wrong produces the {@code foo.csv.pxt} names this dialog exists to avoid.
 * Pure string in, pure string out — no dialog state, so the edge cases are testable.
 *
 * <p>One rule governs both operations: <b>an extension the modeller typed deliberately
 * always wins.</b> The dialog completes a bare name and re-points a suffix it recognises;
 * it never overrules a suffix it doesn't own.
 */
final class SaveNameExtensions {

    private SaveNameExtensions() {
    }

    /**
     * Re-points {@code name} at {@code newExtension} when the type combo changes.
     *
     * <p>The longest suffix matching any {@code declared} extension is stripped before the
     * new one is appended, so {@code .csv} → {@code .res.csv} → {@code .pxt} round-trips
     * cleanly rather than accumulating. A name carrying an extension we don't recognise is
     * returned untouched.
     *
     * @param name         the current contents of the name field
     * @param newExtension the newly chosen type's extension, without the dot (may be null)
     * @param declared     every extension across the dialog's declared types
     */
    static String retarget(String name, String newExtension, List<String> declared) {
        if (name.isEmpty() || newExtension == null) {
            return name;
        }
        String lower = name.toLowerCase();
        String matched = declared.stream()
            .map(String::toLowerCase)
            .filter(extension -> lower.endsWith("." + extension))
            .max(Comparator.comparingInt(String::length))
            .orElse(null);
        if (matched == null) {
            // No suffix of ours to replace: complete a bare name, leave a deliberate one be.
            return hasExtension(name) ? name : name + "." + newExtension;
        }
        return name.substring(0, name.length() - matched.length() - 1) + "." + newExtension;
    }

    /**
     * Completes a name that carries no extension at all with {@code extension}. A name that
     * already has one is untouched whether or not we recognise it — that is what lets
     * callers read the saved format back off the file name.
     *
     * @param extension the active type's extension, without the dot (may be null)
     */
    static String complete(String name, String extension) {
        if (extension == null || name.isEmpty() || hasExtension(name)) {
            return name;
        }
        return name + "." + extension;
    }

    /** Whether the final path segment carries a dot-extension (a leading dot doesn't count). */
    static boolean hasExtension(String name) {
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf(File.separatorChar));
        return name.lastIndexOf('.') > separator + 1;
    }
}
