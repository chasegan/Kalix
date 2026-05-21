package com.kalix.ide.models.optimisation;

import com.kalix.ide.windows.optimisation.ObjectiveConfigPanel.TermRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured, per-optimisation snapshot of everything the Config (GUI) tab edits.
 *
 * <p>This is the backing data model for {@link com.kalix.ide.windows.optimisation.OptimisationGuiBuilder}.
 * The GUI form widgets are treated as stateless views: they are populated from a
 * model via {@code loadFromModel(...)} and read back into a model via
 * {@code captureToModel()}. Each {@link OptimisationInfo} owns one of these so the
 * GUI tab can be restored per optimisation rather than behaving as a single shared
 * widget set.</p>
 *
 * <p>The relationship to the INI text is strictly one-way: a model generates INI
 * text, never the reverse. Once the user edits the INI directly the GUI form is
 * frozen for that optimisation and the model is no longer authoritative.</p>
 */
public class OptimisationConfigModel {

    /** One row of the parameters table: a model parameter and its (possibly blank) expression. */
    public static class ParamEntry {
        public String name;
        public String expression;

        public ParamEntry(String name, String expression) {
            this.name = name != null ? name : "";
            this.expression = expression != null ? expression : "";
        }

        public ParamEntry copy() {
            return new ParamEntry(name, expression);
        }
    }

    // --- Objective ---
    private List<TermRow> terms = new ArrayList<>();
    private String objectiveExpression = "";

    // --- Algorithm ---
    private String algorithm = "SCE";
    private String terminationEvaluations = "60000";
    private String threads = "12";
    private String randomSeed = "";
    private Map<String, String> algorithmSpecificParams = new LinkedHashMap<>();

    // --- Parameters (ordered; expression may be blank for parameters not optimised) ---
    private List<ParamEntry> parameters = new ArrayList<>();

    // --- Objective ---
    public List<TermRow> getTerms() {
        return terms;
    }

    public void setTerms(List<TermRow> terms) {
        this.terms = terms != null ? terms : new ArrayList<>();
    }

    public String getObjectiveExpression() {
        return objectiveExpression;
    }

    public void setObjectiveExpression(String objectiveExpression) {
        this.objectiveExpression = objectiveExpression != null ? objectiveExpression : "";
    }

    // --- Algorithm ---
    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm != null ? algorithm : "";
    }

    public String getTerminationEvaluations() {
        return terminationEvaluations;
    }

    public void setTerminationEvaluations(String terminationEvaluations) {
        this.terminationEvaluations = terminationEvaluations != null ? terminationEvaluations : "";
    }

    public String getThreads() {
        return threads;
    }

    public void setThreads(String threads) {
        this.threads = threads != null ? threads : "";
    }

    public String getRandomSeed() {
        return randomSeed;
    }

    public void setRandomSeed(String randomSeed) {
        this.randomSeed = randomSeed != null ? randomSeed : "";
    }

    public Map<String, String> getAlgorithmSpecificParams() {
        return algorithmSpecificParams;
    }

    public void setAlgorithmSpecificParams(Map<String, String> algorithmSpecificParams) {
        this.algorithmSpecificParams = algorithmSpecificParams != null
            ? new LinkedHashMap<>(algorithmSpecificParams)
            : new LinkedHashMap<>();
    }

    // --- Parameters ---
    public List<ParamEntry> getParameters() {
        return parameters;
    }

    public void setParameters(List<ParamEntry> parameters) {
        this.parameters = parameters != null ? parameters : new ArrayList<>();
    }

    /**
     * Returns a deep copy so a node's stored model can never be mutated by a live
     * GUI form (or vice versa).
     *
     * @return an independent copy of this model
     */
    public OptimisationConfigModel copy() {
        OptimisationConfigModel c = new OptimisationConfigModel();
        for (TermRow t : terms) {
            c.terms.add(t.copy());
        }
        c.objectiveExpression = objectiveExpression;
        c.algorithm = algorithm;
        c.terminationEvaluations = terminationEvaluations;
        c.threads = threads;
        c.randomSeed = randomSeed;
        c.algorithmSpecificParams = new LinkedHashMap<>(algorithmSpecificParams);
        for (ParamEntry p : parameters) {
            c.parameters.add(p.copy());
        }
        return c;
    }
}
