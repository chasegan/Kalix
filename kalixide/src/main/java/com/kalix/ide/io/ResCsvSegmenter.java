package com.kalix.ide.io;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives a series' tree-hierarchy path from a {@code .res.csv} attribute row.
 *
 * <p>This is the one piece of Source-specific structural knowledge in the importer. It does
 * <em>not</em> aim to byte-match the source platform's tree; it adopts a single robust rule
 * that reproduces the platform's structure on the cases observed:</p>
 *
 * <pre>
 *   path = dedupeConsecutive( [WaterFeatureType, Site] + Structure.split("@") )
 * </pre>
 *
 * <p>Worked examples:</p>
 * <ul>
 *   <li>Gauge — {@code WaterFeatureType=Gauge, Site=1002 PI…EOS, Structure=Downstream Flow}
 *       → {@code [Gauge, 1002 PI…EOS, Downstream Flow]}</li>
 *   <li>Function — {@code WaterFeatureType=Functions, Site=Functions,
 *       Structure=Functions@Storages@$f_040196…} → after collapsing the leading run of
 *       {@code Functions} → {@code [Functions, Storages, $f_040196…]}</li>
 * </ul>
 *
 * <p>The {@code @}-split also keeps sibling structures distinct that would otherwise collide
 * on {@code (WaterFeatureType, Site, ElementName)} alone — e.g. two storage spill outlets
 * that differ only by their {@code Structure} suffix.</p>
 *
 * <p>Segments are returned raw (un-sanitised); the caller sanitises each before joining.
 * If every candidate segment is blank the series {@code name} is used as a single-segment
 * fallback so the series is never path-less.</p>
 */
final class ResCsvSegmenter {

    private ResCsvSegmenter() {
        // Utility class — no instantiation
    }

    /**
     * Computes the hierarchy path for one series.
     *
     * @param name the series' descriptive {@code Name} (the fallback when no structure exists)
     * @param waterFeatureType the {@code WaterFeatureType} attribute (may be null/blank)
     * @param site the {@code Site} attribute (may be null/blank)
     * @param structure the {@code Structure} attribute, possibly {@code @}-delimited (may be null/blank)
     * @return the ordered raw segments; never empty
     */
    static List<String> segment(String name, String waterFeatureType, String site, String structure) {
        List<String> raw = new ArrayList<>();
        addIfPresent(raw, waterFeatureType);
        addIfPresent(raw, site);
        if (structure != null) {
            for (String part : structure.split("@", -1)) {
                addIfPresent(raw, part);
            }
        }

        List<String> collapsed = dedupeConsecutive(raw);
        if (collapsed.isEmpty()) {
            collapsed.add(name != null && !name.trim().isEmpty() ? name.trim() : "Series");
        }
        return collapsed;
    }

    private static void addIfPresent(List<String> list, String value) {
        if (value != null && !value.trim().isEmpty()) {
            list.add(value.trim());
        }
    }

    /** Collapses runs of identical adjacent segments (e.g. {@code Functions, Functions} → {@code Functions}). */
    private static List<String> dedupeConsecutive(List<String> segments) {
        List<String> result = new ArrayList<>();
        for (String s : segments) {
            if (result.isEmpty() || !result.get(result.size() - 1).equals(s)) {
                result.add(s);
            }
        }
        return result;
    }
}
