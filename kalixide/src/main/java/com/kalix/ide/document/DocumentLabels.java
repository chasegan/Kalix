package com.kalix.ide.document;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The single resolver that projects a {@link ModelSource} to the string shown for it.
 *
 * <p>Per {@code manifestos/identity-and-labels.md} §2.3, display strings come only from
 * here — never hand-built at a call site. The label depends on <em>context</em>: two
 * open models both called {@code model.ini} must be told apart, so a label is a function
 * of the source <b>and</b> the set it is being shown alongside. That is precisely why it
 * must never be stored or used as a key: the same model's label changes as other models
 * open and close around it.</p>
 */
public final class DocumentLabels {

    private DocumentLabels() {
    }

    /**
     * The label for {@code source} when displayed alongside {@code all}.
     *
     * <p>Returns the bare name when it is unambiguous, and qualifies it with the parent
     * folder when another model in the set shares that name — the convention used by
     * every editor with tabs.</p>
     *
     * @param source the model to label
     * @param all    the models it is being shown alongside (may include {@code source})
     * @return the display label, never null
     */
    public static String labelFor(ModelSource source, List<? extends ModelSource> all) {
        if (source == null) {
            return "";
        }
        String name = source.getDisplayName();
        if (all == null) {
            return name;
        }
        // Count matches excluding `source` itself, so a source being labelled against a
        // set it is not part of (a closed target shown against the open models) is still
        // qualified when one of them shares its name.
        int others = 0;
        for (ModelSource other : all) {
            if (other != source && name.equals(other.getDisplayName())) {
                others++;
            }
        }
        return others > 0 ? qualify(source) : name;
    }

    /**
     * The name qualified by its parent folder. An unsaved model has no folder to qualify
     * with; two of those are genuinely indistinguishable, but both are unusable as
     * optimisation targets anyway.
     */
    private static String qualify(ModelSource source) {
        File folder = source.getWorkingDirectory();
        return folder != null
                ? source.getDisplayName() + " — " + folder.getName()
                : source.getDisplayName();
    }

    /**
     * The label for a model that is no longer open, derived from the file captured when
     * it was bound. The open set it was once disambiguated against is gone, so this is
     * the bare file name.
     *
     * @param file the captured backing file, may be {@code null}
     */
    public static String labelForClosed(File file) {
        return file != null ? file.getName() : "Untitled";
    }

    /**
     * Labels for every model in {@code all}, in the same order — the form the combo box
     * needs. Computed in one pass so the ambiguity check is not repeated per row.
     */
    public static List<String> labelsFor(List<? extends ModelSource> all) {
        if (all == null) {
            return List.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (ModelSource source : all) {
            counts.merge(source.getDisplayName(), 1, Integer::sum);
        }
        return all.stream()
                .map(source -> counts.getOrDefault(source.getDisplayName(), 0) > 1
                        ? qualify(source)
                        : source.getDisplayName())
                .toList();
    }
}
