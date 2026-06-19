package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceResCsvImporterTest {

    @TempDir
    Path tempDir;

    /**
     * Header schema row has 6 columns but the attribute rows carry an extra trailing
     * (unlabelled GUID) field — the real Source quirk. The Name column must still resolve
     * by position. Sentinel -9999, empty cells, and whitespace-padded numbers all exercised.
     */
    private static final String SAMPLE =
        "File version,3\n" +
        "Missing data value,-9999\n" +
        "EOM\n" +
        "Project name,Test Project\n" +
        "Source version,5.16.0\n" +
        "Simulation time,2020-01-01 - 2020-01-03\n" +
        "Field,Units,RunName,Name,Site,ElementName\n" +
        "EOC\n" +
        "2\n" +
        "1,ML,Latest Run,Flow at A,Site A,Downstream Flow,e9bf49ab-guid,\n" +
        "2,m,Latest Run,Level at B,Site B,Storage Level,e9bf49ab-guid,\n" +
        "Date,1>Site A>Downstream Flow,2>Site B>Storage Level\n" +
        "EOH\n" +
        "2020-01-01,1.5,10.0\n" +
        "2020-01-02,-9999,\n" +
        "2020-01-03, 3.5 ,12.5\n";

    private File write(String name, String content) throws IOException {
        File f = tempDir.resolve(name).toFile();
        Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
        return f;
    }

    private static long epochMillis(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    @Test
    void parsesSeriesNamesFromNameAttribute() throws IOException {
        SourceResCsvImporter.ResCsvImportResult result =
            SourceResCsvImporter.parse(write("sample.res.csv", SAMPLE));

        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());
        List<NamedSeries> series = result.getSeries();
        assertEquals(2, series.size());
        // Label comes from the Name attribute, not the terser data-column token.
        assertEquals("Flow at A", series.get(0).name());
        assertEquals("Level at B", series.get(1).name());
    }

    @Test
    void parsesTimestampsAndValues() throws IOException {
        SourceResCsvImporter.ResCsvImportResult result =
            SourceResCsvImporter.parse(write("sample.res.csv", SAMPLE));

        TimeSeriesData s0 = result.getSeries().get(0).data();
        long[] ts = s0.getTimestamps();
        assertEquals(3, ts.length);
        assertEquals(epochMillis("2020-01-01"), ts[0]);
        assertEquals(epochMillis("2020-01-03"), ts[2]);

        double[] v0 = s0.getValues();
        assertEquals(1.5, v0[0]);
        assertTrue(Double.isNaN(v0[1]), "sentinel -9999 should become NaN");
        assertEquals(3.5, v0[2], "whitespace-padded value should parse");
    }

    @Test
    void emptyCellBecomesNaN() throws IOException {
        SourceResCsvImporter.ResCsvImportResult result =
            SourceResCsvImporter.parse(write("sample.res.csv", SAMPLE));

        double[] v1 = result.getSeries().get(1).data().getValues();
        assertEquals(10.0, v1[0]);
        assertTrue(Double.isNaN(v1[1]), "empty cell should become NaN");
        assertEquals(12.5, v1[2]);
    }

    @Test
    void withoutDeclaredSentinelMagicValueIsKept() throws IOException {
        // No "Missing data value" line: -9999 is a legitimate value, not missing.
        String noSentinel =
            "File version,3\n" +
            "EOM\n" +
            "Field,Units,Name\n" +
            "EOC\n" +
            "1\n" +
            "1,ML,Flow A\n" +
            "Date,1>Site>Element\n" +
            "EOH\n" +
            "2020-01-01,-9999\n" +
            "2020-01-02,5\n";

        SourceResCsvImporter.ResCsvImportResult result =
            SourceResCsvImporter.parse(write("ns.res.csv", noSentinel));

        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());
        double[] v = result.getSeries().get(0).data().getValues();
        assertEquals(-9999.0, v[0], "no declared sentinel → -9999 stays a real value");
        assertEquals(5.0, v[1]);
    }

    @Test
    void missingEocMarkerIsAFatalError() throws IOException {
        String broken = "File version,3\nMissing data value,-9999\nEOM\nno markers here\n";
        SourceResCsvImporter.ResCsvImportResult result =
            SourceResCsvImporter.parse(write("broken.res.csv", broken));

        assertTrue(result.hasErrors());
        assertTrue(result.getSeries().isEmpty());
    }

    @Test
    void importerProducesHierarchyPaths() throws IOException {
        // Realistic attribute table with WaterFeatureType / Site / ElementName / Structure,
        // exercising both segmentation branches end-to-end.
        String hier =
            "File version,3\n" +
            "Missing data value,-9999\n" +
            "EOM\n" +
            "Field,Units,RunName,ScenarioName,ScenarioInputSetName,Name,Site,ElementName,"
                + "WaterFeatureType,ElementType,Structure,Custom\n" +
            "EOC\n" +
            "2\n" +
            "7,ML.day^-1,Run,Scen,Set,Gauge: 1002 PI Pimpama Creek EOS: Downstream Flow,"
                + "1002 PI Pimpama Creek EOS,Downstream Flow,Gauge,Node,Downstream Flow,guid,\n" +
            "4,mm.day^-1,Run,Scen,Set,Functions@Functions@Storages@$f_040196_mlake_plus_seep,"
                + "Functions,Functions,Functions,Miscellaneous,"
                + "Functions@Storages@$f_040196_mlake_plus_seep,guid,\n" +
            "Date,7>1002 PI Pimpama Creek EOS>Downstream Flow,4>Functions>x\n" +
            "EOH\n" +
            "2020-01-01,1.0,2.0\n" +
            "2020-01-02,3.0,4.0\n";

        SourceResCsvImporter.ResCsvImportResult result =
            SourceResCsvImporter.parse(write("hier.res.csv", hier));
        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());

        List<NamedSeries> series = result.getSeries();
        assertEquals(2, series.size());

        // Column order follows the column-header row: field 7 then field 4.
        assertEquals(List.of("Gauge", "1002 PI Pimpama Creek EOS", "Downstream Flow"),
            series.get(0).path());
        assertEquals(List.of("Functions", "Storages", "$f_040196_mlake_plus_seep"),
            series.get(1).path());
        // Names remain the full descriptive Name (legend label), independent of the path.
        assertEquals("Gauge: 1002 PI Pimpama Creek EOS: Downstream Flow", series.get(0).name());
    }

    @Test
    void headerReaderReturnsSeriesNames() throws IOException {
        File f = write("sample.res.csv", SAMPLE);
        SourceResCsvHeaderReader reader = new SourceResCsvHeaderReader();
        assertTrue(reader.canRead("sample.res.csv"));
        assertFalse(reader.canRead("plain.csv"));

        List<String> names = reader.readSeriesNames(f);
        // Names are cleansed (non-alphanumerics → underscore) like the other header readers.
        assertEquals(List.of("Flow_at_A", "Level_at_B"), names);
    }
}
