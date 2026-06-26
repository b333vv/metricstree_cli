package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineFileTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldSerializeAndDeserializeBaselineFile() throws IOException {
        BaselineEntry entry = new BaselineEntry("WMC", 55.0, 0.0, 30.0);
        BaselineFile baseline = BaselineFile.create(Map.of(
                "com.example.MyClass", List.of(entry)));

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(baseline);
        assertNotNull(json);
        assertTrue(json.contains("\"version\" : \"1.0\""));
        assertTrue(json.contains("com.example.MyClass"));
        assertTrue(json.contains("WMC"));

        BaselineFile deserialized = mapper.readValue(json, BaselineFile.class);
        assertEquals("1.0", deserialized.version());
        assertNotNull(deserialized.generatedAt());
        assertEquals(1, deserialized.violations().size());

        List<BaselineEntry> entries = deserialized.entriesFor("com.example.MyClass");
        assertEquals(1, entries.size());
        assertEquals("WMC", entries.get(0).metricCode());
        assertEquals(55.0, entries.get(0).currentValue(), 1e-9);
        assertEquals(0.0, entries.get(0).minThreshold(), 1e-9);
        assertEquals(30.0, entries.get(0).maxThreshold(), 1e-9);
    }

    @Test
    void shouldHandleMultipleMetricsPerEntity() throws IOException {
        BaselineFile baseline = BaselineFile.create(Map.of(
                "com.example.MyClass", List.of(
                        new BaselineEntry("WMC", 55.0, 0.0, 30.0),
                        new BaselineEntry("CBO", 18.0, 0.0, 14.0)),
                "com.example.MyClass.doSomething()", List.of(
                        new BaselineEntry("CC", 10.0, 0.0, 3.0))));

        String json = mapper.writeValueAsString(baseline);
        BaselineFile deserialized = mapper.readValue(json, BaselineFile.class);

        assertEquals(2, deserialized.entriesFor("com.example.MyClass").size());
        assertEquals(1, deserialized.entriesFor("com.example.MyClass.doSomething()").size());
        assertEquals(0, deserialized.entriesFor("com.example.NonExistent").size());
    }

    @Test
    void shouldWriteToFileAndReadBack() throws IOException {
        BaselineFile baseline = BaselineFile.create(Map.of(
                "com.example.MyClass", List.of(
                        new BaselineEntry("NOM", 20.0, 0.0, 7.0))));

        Path baselinePath = tempDir.resolve("baseline.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(baselinePath.toFile(), baseline);
        assertTrue(Files.exists(baselinePath));

        BaselineFile loaded = mapper.readValue(Files.readString(baselinePath), BaselineFile.class);
        assertEquals("1.0", loaded.version());
        assertEquals(1, loaded.entriesFor("com.example.MyClass").size());
        assertEquals("NOM", loaded.entriesFor("com.example.MyClass").get(0).metricCode());
    }

    @Test
    void shouldHandleEmptyViolations() throws IOException {
        BaselineFile baseline = BaselineFile.create(Map.of());
        String json = mapper.writeValueAsString(baseline);
        BaselineFile deserialized = mapper.readValue(json, BaselineFile.class);
        assertTrue(deserialized.violations().isEmpty());
    }
}
