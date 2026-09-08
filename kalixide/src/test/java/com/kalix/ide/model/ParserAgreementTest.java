package com.kalix.ide.model;

import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The map's parser and the linter's parser are two products of one grammar
 * ({@code IniSyntax}); this pins that they agree on every node, type, location
 * and link across every model file in the repository. A divergence here is the
 * class of bug where a node lints clean but vanishes from the map (or the reverse).
 */
class ParserAgreementTest {

    /** Every .ini under the repository, excluding build output. */
    private static List<Path> repositoryModels() throws IOException {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        try (Stream<Path> paths = Files.walk(repo)) {
            return paths.filter(p -> p.toString().endsWith(".ini"))
                        .filter(p -> !p.toString().contains("/target/")
                                  && !p.toString().contains("/build/")
                                  && !p.toString().contains("/site/"))
                        .sorted()
                        .toList();
        }
    }

    @Test
    void mapAndLinterParsersAgreeOnEveryRepositoryModel() throws IOException {
        List<Path> models = repositoryModels();
        assertFalse(models.isEmpty(), "no model files found under the repository");

        for (Path model : models) {
            String text = Files.readString(model);
            assertParsersAgree(text, model.toString());
        }
    }

    static void assertParsersAgree(String text, String label) {
        INIModelParser.ParsedModel linted = INIModelParser.parse(text);
        ModelParser.ParseResult mapped = ModelParser.parse(text);

        // A name defined twice is two map nodes but one (last-wins) linter node;
        // its type and location are compared only when the name is unique.
        Set<String> duplicateNames = INIModelParser.findDuplicateNodes(linted).keySet();

        // Every map node is a linter node with the same type and location ...
        for (ModelNode node : mapped.getNodes()) {
            INIModelParser.NodeSection section = linted.getNodes().get(node.getName());
            assertNotNull(section, label + ": map node '" + node.getName() + "' unknown to the linter");
            if (duplicateNames.contains(node.getName())) {
                continue;
            }
            assertEquals(section.getNodeType(), node.getType(), label + ": type of " + node.getName());
            String[] loc = section.getProperties().get("loc").getValue().split(",");
            assertEquals(Double.parseDouble(loc[0].trim()), node.getX(), label + ": x of " + node.getName());
            assertEquals(Double.parseDouble(loc[1].trim()), node.getY(), label + ": y of " + node.getName());
        }

        // ... and every linter node with a type and a two-number loc is on the map.
        Set<String> expectedOnMap = new TreeSet<>();
        for (INIModelParser.NodeSection section : linted.getNodes().values()) {
            INIModelParser.Property loc = section.getProperties().get("loc");
            if (section.getNodeType() != null && loc != null && isCoordinatePair(loc.getValue())) {
                expectedOnMap.add(section.getNodeName());
            }
        }
        Set<String> onMap = mapped.getNodes().stream().map(ModelNode::getName).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(expectedOnMap, onMap, label + ": nodes on the map");

        // Links: the map's ds_N links equal the linter's downstream references of mapped nodes.
        Set<String> mapLinks = mapped.getLinks().stream()
                .map(l -> l.getUpstreamTerminus() + ">" + l.getDownstreamTerminus())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> lintLinks = new TreeSet<>();
        for (INIModelParser.NodeSection section : linted.getAllNodeSections()) {
            if (!onMap.contains(section.getNodeName())) {
                continue;
            }
            for (INIModelParser.Property prop : section.getAllProperties()) {
                if (com.kalix.ide.linter.utils.ValidationUtils.isDsNodeParam(prop.getKey())) {
                    lintLinks.add(section.getNodeName() + ">" + prop.getValue());
                }
            }
        }
        assertEquals(lintLinks, mapLinks, label + ": links");
    }

    private static boolean isCoordinatePair(String value) {
        String[] parts = value.split(",", -1);
        if (parts.length != 2) {
            return false;
        }
        try {
            Double.parseDouble(parts[0].trim());
            Double.parseDouble(parts[1].trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
