package com.kalix.ide.editor.autocomplete;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.fife.ui.autocomplete.Completion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JTextArea;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine registers an aliased {@code [data]} file under both its alias and its
 * file name, so value completions offer both forms of {@code data.*} reference.
 */
class KalixCompletionProviderDataAliasTest {

    private static final String FILE = "inputs/G8170008_MLd.csv";

    @TempDir
    Path baseDir;

    private InputDataRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.dispose();
        }
    }

    /** Polls until the condition holds or a generous timeout elapses. */
    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                return;  // let the caller's assertion report the failure
            }
            Thread.sleep(10);
        }
    }

    /** Replacement texts offered with the caret at the end of {@code text}. */
    private List<String> completionsAtEndOf(String text) throws InterruptedException {
        INIModelParser.ParsedModel model = INIModelParser.parse(text);
        registry = new InputDataRegistry(() -> baseDir.toFile());
        registry.refresh(model.getInputFiles());
        awaitTrue(() -> registry.getDataSources().keySet().containsAll(model.getInputFiles()));

        KalixCompletionProvider provider =
                new KalixCompletionProvider(LinterSchema.loadDefault(), () -> model, registry, null);
        JTextArea editor = new JTextArea(text);
        editor.setCaretPosition(text.length());
        return provider.getCompletions(editor).stream().map(Completion::getReplacementText).toList();
    }

    private void writeGaugeFile() throws IOException {
        Files.createDirectories(baseDir.resolve("inputs"));
        Files.writeString(baseDir.resolve(FILE), "Date,Value (ML/d)\n1981-08-28,1.5\n");
    }

    private static String modelWithData(String dataLines) {
        return "[data]\n" + dataLines + "\n[node.gauge]\ntype = inflow\ninflow = data.";
    }

    @Test
    void aliasedFileIsOfferedByAliasAndByFileName() throws IOException, InterruptedException {
        writeGaugeFile();

        List<String> offered = completionsAtEndOf(modelWithData("GaugeFlow-8170008 = " + FILE + "\n"));

        assertEquals(List.of(
                "data.g8170008_mld_csv.by_index.1",
                "data.g8170008_mld_csv.by_name.value__ml_d_",
                "data.gaugeflow_8170008.by_index.1",
                "data.gaugeflow_8170008.by_name.value__ml_d_"), offered);
    }

    @Test
    void bareDeclarationOffersNothing() throws IOException, InterruptedException {
        writeGaugeFile();

        List<String> offered = completionsAtEndOf(modelWithData("observed =\n" + FILE + "\n"));

        assertEquals(List.of(
                "data.g8170008_mld_csv.by_index.1",
                "data.g8170008_mld_csv.by_name.value__ml_d_"), offered);
    }

    @Test
    void aliasMatchingTheFileNameSourceIsOfferedOnce() throws IOException, InterruptedException {
        writeGaugeFile();

        List<String> offered = completionsAtEndOf(modelWithData("G8170008_MLd.csv = " + FILE + "\n"));

        assertEquals(List.of(
                "data.g8170008_mld_csv.by_index.1",
                "data.g8170008_mld_csv.by_name.value__ml_d_"), offered);
    }

    @Test
    void aliasOfAnUnreadFileStillOffersByIndex() throws InterruptedException {
        List<String> offered = completionsAtEndOf(modelWithData("gauge = missing.csv\n"));

        assertTrue(offered.contains("data.gauge.by_index.1"));
        assertTrue(offered.contains("data.missing_csv.by_index.1"));
        assertFalse(offered.stream().anyMatch(text -> text.contains(".by_name.")));
    }
}
