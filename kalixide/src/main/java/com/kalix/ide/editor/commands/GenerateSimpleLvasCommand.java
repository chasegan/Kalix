package com.kalix.ide.editor.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;

/**
 * Command to generate a simple Level-Volume-Area-Spill (LVAS) table for storage nodes.
 * Prompts user for full supply volume and area, then generates a CSV table.
 */
public class GenerateSimpleLvasCommand implements EditorCommand {

    private static final Logger logger = LoggerFactory.getLogger(GenerateSimpleLvasCommand.class);

    private final CommandMetadata metadata;
    private final JFrame parentFrame;

    public GenerateSimpleLvasCommand(JFrame parentFrame) {
        this.parentFrame = parentFrame;
        this.metadata = new CommandMetadata.Builder()
            .id("generate_simple_lvas")
            .displayName("Generate Pyramidal Dimensions")
            .description("Generate a simple Level-Volume-Area-Spill table for storage node")
            .category("")
            .build();
    }

    @Override
    public CommandMetadata getMetadata() {
        return metadata;
    }

    @Override
    public boolean isApplicable(EditorContext context) {
        // Only applicable when cursor is on the "dimensions" property of a "storage" node
        return context.getType() == EditorContext.ContextType.PROPERTY
            && context.getPropertyKey().isPresent()
            && context.getPropertyKey().get().equals("dimensions")
            && context.getNodeType().isPresent()
            && context.getNodeType().get().equals("storage");
    }

    @Override
    public void execute(EditorContext context, CommandExecutor executor) {
        // Show dialog to get parameters
        LvasParameters params = promptForParameters();
        if (params == null) {
            // User cancelled
            return;
        }

        // Generate the LVAS table
        String lvasTable = generateLvas(params.fsVolume, params.fsArea);

        // Insert at cursor position
        executor.insertTextAtCursor(lvasTable);

        logger.debug("Generated LVAS table with fs_volume={}, fs_area={}", params.fsVolume, params.fsArea);
    }

    /**
     * Prompts the user for LVAS parameters.
     *
     * @return LvasParameters if OK clicked, null if cancelled
     */
    private LvasParameters promptForParameters() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Full supply volume field
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Full supply volume [ML]:"), gbc);

        gbc.gridx = 1;
        JTextField volumeField = new JTextField("10000", 15);
        panel.add(volumeField, gbc);

        // Full supply area field
        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(new JLabel("Full supply area [km2]:"), gbc);

        gbc.gridx = 1;
        JTextField areaField = new JTextField("3.0", 15);
        panel.add(areaField, gbc);

        // Show dialog
        int result = JOptionPane.showConfirmDialog(
            parentFrame,
            panel,
            "Generate Simple LVAS",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        );

        if (result != JOptionPane.OK_OPTION) {
            return null;
        }

        // Parse values
        try {
            double fsVolume = Double.parseDouble(volumeField.getText().trim());
            double fsArea = Double.parseDouble(areaField.getText().trim());
            return new LvasParameters(fsVolume, fsArea);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(
                parentFrame,
                "Invalid numeric values entered",
                "Error",
                JOptionPane.ERROR_MESSAGE
            );
            return null;
        }
    }

    /**
     * Generates the LVAS CSV table.
     * Based on Python code:
     * <pre>
     * def generate_lvas(fs_volume, fs_area):
     *     BIG_VOL = 1e9
     *     BIG_SPILL = 1e9
     *     answer = "Level [m], Volume [ML], Area [km2], Spill [ML],\n"
     *     for v, s in [(0, 0), (fs_volume, 0), (fs_volume+1, BIG_SPILL), (BIG_VOL, BIG_SPILL)]:
     *         (level, _, area) = pyramid_dims(fs_volume, fs_area, v)
     *         answer += f"    {level}, {v}, {area}, {s},\n"
     *     return answer
     * </pre>
     *
     * @param fsVolume Full supply volume [ML]
     * @param fsArea   Full supply area [km2]
     * @return CSV table string
     */
    private String generateLvas(double fsVolume, double fsArea) {
        final double BIG_VOL = 1e9;
        final double BIG_SPILL = 1e9;

        StringBuilder sb = new StringBuilder();
        sb.append("Level [m], Volume [ML], Area [km2], Spill [ML],\n");

        // Define the rows: (volume, spill)
        double[][] rows = {
            {0, 0},
            {fsVolume, 0},
            {fsVolume + 1, BIG_SPILL},
            {BIG_VOL, BIG_SPILL}
        };

        for (double[] row : rows) {
            double volume = row[0];
            double spill = row[1];
            PyramidDims dims = calculatePyramidDims(fsVolume, fsArea, volume);

            sb.append(String.format("    %g, %g, %g, %g,\n",
                dims.level, volume, dims.area, spill));
        }

        return sb.toString();
    }

    /**
     * Calculates pyramid dimensions.
     * Based on Python code:
     * <pre>
     * def pyramid_dims(fs_volume, fs_area, volume):
     *     fs_level = 3.0 * (fs_volume / 1000.0) / fs_area
     *     level = math.sqrt(3.0 * (volume / 1000.0) * (fs_level / fs_area))
     *     area = 3.0 * (volume / 1000.0) / level if (volume > 0) else 0
     *     return((level, volume, area))
     * </pre>
     *
     * @param fsVolume Full supply volume [ML]
     * @param fsArea   Full supply area [km2]
     * @param volume   Volume [ML]
     * @return PyramidDims containing level, volume, and area
     */
    private PyramidDims calculatePyramidDims(double fsVolume, double fsArea, double volume) {
        double fsLevel = 3.0 * (fsVolume / 1000.0) / fsArea;
        double level = Math.sqrt(3.0 * (volume / 1000.0) * (fsLevel / fsArea));
        double area = (volume > 0) ? (3.0 * (volume / 1000.0) / level) : 0;

        return new PyramidDims(level, volume, area);
    }

    /**
     * Parameters for LVAS generation.
     */
    private static class LvasParameters {
        final double fsVolume;
        final double fsArea;

        LvasParameters(double fsVolume, double fsArea) {
            this.fsVolume = fsVolume;
            this.fsArea = fsArea;
        }
    }

    /**
     * Result of pyramid dimension calculation.
     */
    private static class PyramidDims {
        final double level;
        final double volume;
        final double area;

        PyramidDims(double level, double volume, double area) {
            this.level = level;
            this.volume = volume;
            this.area = area;
        }
    }
}
