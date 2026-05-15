# Detect Command Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `detect` subcommand that checks classes/packages against named metric rules (antipatterns) and writes a JSON report with matching entities.

**Architecture:** Thin picocli command delegates to a stateless detector service. Input rules and output results are plain records serialized via Jackson. Detection is purely in the CLI module (no library changes).

**Tech Stack:** Java 17, picocli 4.7.6, Jackson 2.17.2, JUnit 5, Gradle 8.8

---

## File Structure

### Create:
- `java-metrics-cli/src/main/java/org/b333vv/metric/cli/CombinationDefinition.java`
- `java-metrics-cli/src/main/java/org/b333vv/metric/cli/CombinationDetector.java`
- `java-metrics-cli/src/main/java/org/b333vv/metric/cli/DetectResultWriter.java`
- `java-metrics-cli/src/main/java/org/b333vv/metric/cli/DetectCommand.java`
- `java-metrics-cli/src/test/java/org/b333vv/metric/cli/CombinationDetectorTest.java`
- `java-metrics-cli/src/test/java/org/b333vv/metric/cli/DetectCommandTest.java`

### Modify:
- `java-metrics-cli/src/main/java/org/b333vv/metric/cli/JavaMetricsCliApplication.java` — register `detect` subcommand

---

### Task 1: CombinationDefinition Records

**Files:**
- Create: `java-metrics-cli/src/main/java/org/b333vv/metric/cli/CombinationDefinition.java`

- [ ] **Step 1: Create CombinationDefinition.java**

```java
package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

record CombinationDefinition(
        @JsonProperty("name") String name,
        @JsonProperty("conditions") List<Condition> conditions) {

    CombinationDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("conditions must not be empty");
        }
    }
}

record Condition(
        @JsonProperty("metric") String metric,
        @JsonProperty("min") Double min,
        @JsonProperty("max") Double max) {

    Condition {
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("metric must not be blank");
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :java-metrics-cli:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add java-metrics-cli/src/main/java/org/b333vv/metric/cli/CombinationDefinition.java
git commit -m "feat: add CombinationDefinition input model records for detect command"
```

---

### Task 2: CombinationDetector Implementation + Tests (TDD)

**Files:**
- Create: `java-metrics-cli/src/main/java/org/b333vv/metric/cli/CombinationDetector.java`
- Create: `java-metrics-cli/src/test/java/org/b333vv/metric/cli/CombinationDetectorTest.java`

- [ ] **Step 1: Write the failing CombinationDetectorTest**

```java
package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.*;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CombinationDetectorTest {

    private final CombinationDetector detector = new CombinationDetector();

    @Test
    void singleConditionMatchesClass() {
        MetricReport report = createReport(
                Map.of(MetricCode.WMC, Value.of(50)),
                Map.of());
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("LargeClass",
                        List.of(new Condition("WMC", 47.0, null))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertEquals(1, result.size());
        assertEquals("LargeClass", result.get(0).name());
        assertEquals(1, result.get(0).matches().size());
        assertEquals("com.example.MyClass", result.get(0).matches().get(0).qualifiedName());
    }

    @Test
    void multipleAndConditionsMatchOnlyWhenAllPass() {
        MetricReport report = createReport(
                Map.of(MetricCode.WMC, Value.of(50), MetricCode.ATFD, Value.of(12)),
                Map.of());
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("GodClass",
                        List.of(new Condition("WMC", 47.0, null),
                                new Condition("ATFD", 10.0, null))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertEquals(1, result.size());
    }

    @Test
    void conditionFails_whenMetricBelowMin() {
        MetricReport report = createReport(
                Map.of(MetricCode.WMC, Value.of(5)),
                Map.of());
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("LargeClass",
                        List.of(new Condition("WMC", 47.0, null))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertTrue(result.isEmpty());
    }

    @Test
    void conditionFails_whenMetricAboveMax() {
        MetricReport report = createReport(
                Map.of(MetricCode.TCC, Value.of(0.9)),
                Map.of());
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("NonCohesive",
                        List.of(new Condition("TCC", null, 0.33))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertTrue(result.isEmpty());
    }

    @Test
    void conditionMatches_whenMetricWithinBothBounds() {
        MetricReport report = createReport(
                Map.of(MetricCode.WMC, Value.of(30)),
                Map.of());
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("NormalRange",
                        List.of(new Condition("WMC", 10.0, 47.0))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertEquals(1, result.size());
    }

    @Test
    void missingMetricDoesNotMatch() {
        MetricReport report = createReport(
                Map.of(MetricCode.NOM, Value.of(5)),
                Map.of());
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("GodClass",
                        List.of(new Condition("WMC", 47.0, null))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertTrue(result.isEmpty());
    }

    @Test
    void emptyRulesListProducesEmptyResult() {
        MetricReport report = createReport(Map.of(), Map.of());

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, List.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void packageDetectionWorksAnalogously() {
        MetricReport report = createReport(
                Map.of(),
                Map.of(MetricCode.PLOC, Value.of(5000)));
        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("LargePackage",
                        List.of(new Condition("PLOC", 1000.0, null))));

        List<CombinationDetector.PackageMatch> result = detector.detectPackages(report, rules);

        assertEquals(1, result.size());
        assertEquals("LargePackage", result.get(0).name());
        assertEquals(1, result.get(0).matches().size());
        assertEquals("com.example", result.get(0).matches().get(0).packageName());
    }

    @Test
    void multipleClassesCanMatchSameRule() {
        ClassReport class1 = new ClassReport(
                "ClassA", "com.example.ClassA", Path.of("ClassA.java"),
                new SourceLocation(Path.of("ClassA.java"), 1, 10),
                Map.of(MetricCode.WMC, Value.of(50)), List.of());
        ClassReport class2 = new ClassReport(
                "ClassB", "com.example.ClassB", Path.of("ClassB.java"),
                new SourceLocation(Path.of("ClassB.java"), 1, 10),
                Map.of(MetricCode.WMC, Value.of(60)), List.of());
        ClassReport class3 = new ClassReport(
                "ClassC", "com.example.ClassC", Path.of("ClassC.java"),
                new SourceLocation(Path.of("ClassC.java"), 1, 10),
                Map.of(MetricCode.WMC, Value.of(5)), List.of());

        PackageReport pkg = new PackageReport("com.example", Map.of(), List.of(class1, class2, class3));
        MetricReport report = new MetricReport(
                new ProjectReport("test", Map.of(), List.of(pkg)),
                List.of());

        List<CombinationDefinition> rules = List.of(
                new CombinationDefinition("LargeClass",
                        List.of(new Condition("WMC", 47.0, null))));

        List<CombinationDetector.ClassMatch> result = detector.detectClasses(report, rules);

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).matches().size());
    }

    private static MetricReport createReport(
            Map<MetricCode, Value> classMetrics,
            Map<MetricCode, Value> packageMetrics) {
        ClassReport classReport = new ClassReport(
                "MyClass", "com.example.MyClass",
                Path.of("src/MyClass.java"),
                new SourceLocation(Path.of("src/MyClass.java"), 1, 10),
                classMetrics,
                List.of());
        PackageReport packageReport = new PackageReport(
                "com.example",
                packageMetrics,
                List.of(classReport));
        return new MetricReport(
                new ProjectReport("test", Map.of(), List.of(packageReport)),
                List.of());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :java-metrics-cli:test --tests "org.b333vv.metric.cli.CombinationDetectorTest" 2>&1`
Expected: Compilation error — `CombinationDetector` does not exist

- [ ] **Step 3: Write minimal CombinationDetector implementation**

```java
package org.b333vv.metric.cli;

import org.b333vv.metric.library.core.ClassReport;
import org.b333vv.metric.library.core.MetricCode;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.PackageReport;
import org.b333vv.metric.model.metric.value.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class CombinationDetector {

    record ClassEntityRef(String className, String qualifiedName, String sourcePath) {}
    record PackageEntityRef(String packageName) {}
    record ClassMatch(String name, int matchCount, List<ClassEntityRef> matches) {}
    record PackageMatch(String name, int matchCount, List<PackageEntityRef> matches) {}

    List<ClassMatch> detectClasses(MetricReport report, List<CombinationDefinition> rules) {
        List<ClassMatch> results = new ArrayList<>();
        for (CombinationDefinition rule : rules) {
            List<ClassEntityRef> matched = new ArrayList<>();
            for (ClassReport cls : report.classes()) {
                if (matchesAll(cls.metrics(), rule.conditions())) {
                    matched.add(new ClassEntityRef(
                            cls.className(),
                            cls.qualifiedName(),
                            cls.sourcePath().toString()));
                }
            }
            if (!matched.isEmpty()) {
                results.add(new ClassMatch(rule.name(), matched.size(), matched));
            }
        }
        return results;
    }

    List<PackageMatch> detectPackages(MetricReport report, List<CombinationDefinition> rules) {
        List<PackageMatch> results = new ArrayList<>();
        for (CombinationDefinition rule : rules) {
            List<PackageEntityRef> matched = new ArrayList<>();
            for (PackageReport pkg : report.packages()) {
                if (matchesAll(pkg.metrics(), rule.conditions())) {
                    matched.add(new PackageEntityRef(pkg.packageName()));
                }
            }
            if (!matched.isEmpty()) {
                results.add(new PackageMatch(rule.name(), matched.size(), matched));
            }
        }
        return results;
    }

    private static boolean matchesAll(Map<MetricCode, Value> metrics, List<Condition> conditions) {
        for (Condition condition : conditions) {
            MetricCode code = MetricCode.valueOf(condition.metric());
            Value value = metrics.get(code);
            if (value == null) {
                return false;
            }
            double v = value.doubleValue();
            if (condition.min() != null && v < condition.min()) {
                return false;
            }
            if (condition.max() != null && v > condition.max()) {
                return false;
            }
        }
        return true;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :java-metrics-cli:test --tests "org.b333vv.metric.cli.CombinationDetectorTest" 2>&1`
Expected: All 9 tests pass

- [ ] **Step 5: Commit**

```bash
git add java-metrics-cli/src/main/java/org/b333vv/metric/cli/CombinationDetector.java java-metrics-cli/src/test/java/org/b333vv/metric/cli/CombinationDetectorTest.java
git commit -m "feat: add CombinationDetector with class and package rule matching"
```

---

### Task 3: DetectResultWriter Implementation + Tests (TDD)

**Files:**
- Create: `java-metrics-cli/src/main/java/org/b333vv/metric/cli/DetectResultWriter.java`
- Modify: `java-metrics-cli/src/test/java/org/b333vv/metric/cli/CombinationDetectorTest.java`

- [ ] **Step 1: Write DetectResultWriterTest (inline, then implement)**

Add to `CombinationDetectorTest.java` or create separate. Let's add a few tests at the end of `CombinationDetectorTest.java`:

Append to `CombinationDetectorTest.java`:

```java
// --- DetectResultWriter tests ---

@Test
void writerProducesExpectedJsonStructure() throws Exception {
    List<CombinationDetector.ClassMatch> classMatches = List.of(
            new CombinationDetector.ClassMatch("GodClass", 1,
                    List.of(new CombinationDetector.ClassEntityRef(
                            "MyClass", "com.example.MyClass", "src/MyClass.java"))));
    List<CombinationDetector.PackageMatch> packageMatches = List.of(
            new CombinationDetector.PackageMatch("LargePackage", 1,
                    List.of(new CombinationDetector.PackageEntityRef("com.example"))));

    String json = new DetectResultWriter().toJson(classMatches, 1, packageMatches, 1);

    assertTrue(json.contains("\"status\":\"COMPLETED\""));
    assertTrue(json.contains("\"name\":\"GodClass\""));
    assertTrue(json.contains("\"name\":\"LargePackage\""));
    assertTrue(json.contains("\"total\":1"));
    assertTrue(json.contains("\"matched\":1"));
}

@Test
void writerOmitsEmptyRuleLists() throws Exception {
    String json = new DetectResultWriter().toJson(List.of(), 0, List.of(), 0);

    assertTrue(json.contains("\"classRules\":[]"));
    assertTrue(json.contains("\"packageRules\":[]"));
}

@Test
void writerProducesValidJsonWhenOnlyClassRules() throws Exception {
    List<CombinationDetector.ClassMatch> classMatches = List.of(
            new CombinationDetector.ClassMatch("GodClass", 1,
                    List.of(new CombinationDetector.ClassEntityRef(
                            "MyClass", "com.example.MyClass", "src/MyClass.java"))));

    String json = new DetectResultWriter().toJson(classMatches, 2, List.of(), 0);

    assertTrue(json.contains("\"classRules\""));
    assertTrue(json.contains("\"packageRules\":[]"));
}

@Test
void writerHandlesMatchedCountMismatch() throws Exception {
    List<CombinationDetector.ClassMatch> matches = List.of();
    String json = new DetectResultWriter().toJson(matches, 3, List.of(), 0);

    assertTrue(json.contains("\"total\":3"));
    assertTrue(json.contains("\"matched\":0"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :java-metrics-cli:test --tests "org.b333vv.metric.cli.CombinationDetectorTest" 2>&1`
Expected: Compilation error — `DetectResultWriter` does not exist

- [ ] **Step 3: Write DetectResultWriter implementation**

```java
package org.b333vv.metric.cli;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

final class DetectResultWriter {

    private final ObjectMapper mapper = new ObjectMapper();

    String toJson(
            List<CombinationDetector.ClassMatch> classMatches,
            int classRulesTotal,
            List<CombinationDetector.PackageMatch> packageMatches,
            int packageRulesTotal) throws JsonProcessingException {
        return mapper.writeValueAsString(new DetectResultView(
                "COMPLETED",
                classMatches,
                packageMatches,
                new SummaryView(
                        new RulesSummary(classRulesTotal, classMatches.size()),
                        new RulesSummary(packageRulesTotal, packageMatches.size()))));
    }

    private record DetectResultView(
            @JsonProperty("status") String status,
            @JsonProperty("classRules") List<CombinationDetector.ClassMatch> classRules,
            @JsonProperty("packageRules") List<CombinationDetector.PackageMatch> packageRules,
            @JsonProperty("summary") SummaryView summary) {}

    private record SummaryView(
            @JsonProperty("classRules") RulesSummary classRules,
            @JsonProperty("packageRules") RulesSummary packageRules) {}

    private record RulesSummary(
            @JsonProperty("total") int total,
            @JsonProperty("matched") int matched) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :java-metrics-cli:test --tests "org.b333vv.metric.cli.CombinationDetectorTest" 2>&1`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add java-metrics-cli/src/main/java/org/b333vv/metric/cli/DetectResultWriter.java
git commit -m "feat: add DetectResultWriter for detect command JSON output"
```

---

### Task 4: DetectCommand + CLI Registration

**Files:**
- Create: `java-metrics-cli/src/main/java/org/b333vv/metric/cli/DetectCommand.java`
- Modify: `java-metrics-cli/src/main/java/org/b333vv/metric/cli/JavaMetricsCliApplication.java`

- [ ] **Step 1: Write DetectCommandTest**

```java
package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.b333vv.metric.library.core.*;
import org.b333vv.metric.library.javaparser.JavaMetricsAnalyzer;
import org.b333vv.metric.model.metric.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class DetectCommandTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JavaMetricsCliApplication createApp(JavaMetricsAnalyzer analyzer) {
        return new JavaMetricsCliApplication(
                analyzer,
                new MetricReportJsonWriter(),
                () -> Path.of("."));
    }

    @Test
    void detectWithClassRulesProducesOutput(@TempDir Path tempDir) throws Exception {
        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, """
                [{"name":"LargeClass","conditions":[{"metric":"WMC","min":10}]}]
                """);
        Path outputFile = tempDir.resolve("output.json");
        Path sourceFile = tempDir.resolve("Demo.java");
        Files.writeString(sourceFile, "class Demo {}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = createApp(new JavaMetricsAnalyzer() {
            @Override
            public MetricReport analyze(AnalysisRequest request) {
                return createReport();
            }
        }).run(new String[]{
                "detect",
                "-s", sourceFile.toString(),
                "--class-rules", rulesFile.toString(),
                "-o", outputFile.toString()
        }, out, err);

        assertEquals(0, exitCode);
        assertTrue(Files.exists(outputFile));
        String json = Files.readString(outputFile);
        JsonNode root = mapper.readTree(json);
        assertEquals("COMPLETED", root.get("status").asText());
        assertTrue(root.has("classRules"));
        assertTrue(root.has("packageRules"));
        assertTrue(root.has("summary"));
    }

    @Test
    void detectWithPackageRulesProducesOutput(@TempDir Path tempDir) throws Exception {
        Path rulesFile = tempDir.resolve("pkg-rules.json");
        Files.writeString(rulesFile, """
                [{"name":"LargePackage","conditions":[{"metric":"PLOC","min":1000}]}]
                """);
        Path outputFile = tempDir.resolve("output.json");
        Path sourceFile = tempDir.resolve("Demo.java");
        Files.writeString(sourceFile, "class Demo {}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = createApp(new JavaMetricsAnalyzer() {
            @Override
            public MetricReport analyze(AnalysisRequest request) {
                return createReport();
            }
        }).run(new String[]{
                "detect",
                "-s", sourceFile.toString(),
                "--package-rules", rulesFile.toString(),
                "-o", outputFile.toString()
        }, out, err);

        assertEquals(0, exitCode);
        assertTrue(Files.exists(outputFile));
    }

    @Test
    void detectFailsWhenNoRulesProvided(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("output.json");
        Path sourceFile = tempDir.resolve("Demo.java");
        Files.writeString(sourceFile, "class Demo {}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = createApp( request -> createReport()).run(new String[]{
                "detect",
                "-s", sourceFile.toString(),
                "-o", outputFile.toString()
        }, out, err);

        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("class-rules") || err.toString().contains("package-rules"));
    }

    @Test
    void detectFailsWhenRulesFileInvalid(@TempDir Path tempDir) throws Exception {
        Path rulesFile = tempDir.resolve("bad.json");
        Files.writeString(rulesFile, "not json");
        Path outputFile = tempDir.resolve("output.json");
        Path sourceFile = tempDir.resolve("Demo.java");
        Files.writeString(sourceFile, "class Demo {}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = createApp(request -> createReport()).run(new String[]{
                "detect",
                "-s", sourceFile.toString(),
                "--class-rules", rulesFile.toString(),
                "-o", outputFile.toString()
        }, out, err);

        assertEquals(1, exitCode);
    }

    private static MetricReport createReport() {
        ClassReport cls = new ClassReport(
                "Demo", "Demo", Path.of("Demo.java"),
                new SourceLocation(Path.of("Demo.java"), 1, 1),
                Map.of(MetricCode.WMC, Value.of(50)),
                List.of());
        PackageReport pkg = new PackageReport("", Map.of(MetricCode.PLOC, Value.of(5000)), List.of(cls));
        return new MetricReport(new ProjectReport("test", Map.of(), List.of(pkg)), List.of());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :java-metrics-cli:test --tests "org.b333vv.metric.cli.DetectCommandTest" 2>&1`
Expected: Compilation error — `DetectCommand` does not exist

- [ ] **Step 3: Write DetectCommand implementation**

```java
package org.b333vv.metric.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.b333vv.metric.library.core.AnalysisOptions;
import org.b333vv.metric.library.core.AnalysisRequest;
import org.b333vv.metric.library.core.MetricReport;
import org.b333vv.metric.library.core.SourceRoot;
import org.b333vv.metric.library.core.SourceUnit;
import org.b333vv.metric.library.javaparser.JavaMetricsAnalyzer;
import picocli.CommandLine;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

@CommandLine.Command(
        name = "detect",
        description = "Detect metric rule matches (antipatterns / fitness functions).")
final class DetectCommand implements Callable<Integer> {

    private final JavaMetricsAnalyzer analyzer;
    private final Supplier<Path> currentWorkingDirectorySupplier;
    private final PrintWriter stdout;
    private final PrintWriter stderr;

    DetectCommand(
            JavaMetricsAnalyzer analyzer,
            Supplier<Path> currentWorkingDirectorySupplier,
            PrintWriter stdout,
            PrintWriter stderr) {
        this.analyzer = analyzer;
        this.currentWorkingDirectorySupplier = currentWorkingDirectorySupplier;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(names = {"-s", "--source"}, required = true, paramLabel = "PATH",
            description = "Source root scanned recursively for .java files or explicit Java source file.")
    private Path source;

    @CommandLine.Option(names = "--class-rules", paramLabel = "PATH",
            description = "JSON file with class-level rule definitions.")
    private Path classRulesFile;

    @CommandLine.Option(names = "--package-rules", paramLabel = "PATH",
            description = "JSON file with package-level rule definitions.")
    private Path packageRulesFile;

    @CommandLine.Option(names = {"-o", "--output"}, required = true, paramLabel = "PATH",
            description = "Path to write JSON report.")
    private Path outputFile;

    @Override
    public Integer call() throws IOException {
        if (classRulesFile == null && packageRulesFile == null) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "At least one of --class-rules or --package-rules must be provided.");
        }

        List<SourceRoot> sourceRoots = new ArrayList<>();
        List<SourceUnit> sourceUnits = new ArrayList<>();
        if (Files.isDirectory(source)) {
            sourceRoots.add(new SourceRoot(source));
        } else if (Files.isRegularFile(source) && source.toString().endsWith(".java")) {
            sourceUnits.add(new SourceUnit(source));
        } else {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "Source must be a .java file or directory containing .java files.");
        }

        AnalysisRequest request = new AnalysisRequest(
                "detect", sourceRoots, sourceUnits, List.of(),
                AnalysisOptions.defaults());

        MetricReport report = analyzer.analyze(request);

        ObjectMapper mapper = new ObjectMapper();
        CombinationDetector detector = new CombinationDetector();

        List<CombinationDetector.ClassMatch> classMatches = List.of();
        int classRulesTotal = 0;
        if (classRulesFile != null) {
            List<CombinationDefinition> classRules = mapper.readValue(
                    Files.readString(classRulesFile),
                    new TypeReference<List<CombinationDefinition>>() {});
            classRulesTotal = classRules.size();
            classMatches = detector.detectClasses(report, classRules);
        }

        List<CombinationDetector.PackageMatch> packageMatches = List.of();
        int packageRulesTotal = 0;
        if (packageRulesFile != null) {
            List<CombinationDefinition> packageRules = mapper.readValue(
                    Files.readString(packageRulesFile),
                    new TypeReference<List<CombinationDefinition>>() {});
            packageRulesTotal = packageRules.size();
            packageMatches = detector.detectPackages(report, packageRules);
        }

        String json = new DetectResultWriter().toJson(
                classMatches, classRulesTotal, packageMatches, packageRulesTotal);

        Path normalizedOutputFile = outputFile.toAbsolutePath().normalize();
        if (normalizedOutputFile.getParent() != null) {
            Files.createDirectories(normalizedOutputFile.getParent());
        }
        Files.writeString(normalizedOutputFile, json);
        return 0;
    }
}
```

- [ ] **Step 4: Register in JavaMetricsCliApplication**

Edit `JavaMetricsCliApplication.java:36-41`:

Old:
```java
AnalyzeCommand analyzeCommand = new AnalyzeCommand(analyzer, jsonWriter, currentWorkingDirectorySupplier, stdout, stderr);
ValidateCommand validateCommand = new ValidateCommand(analyzer, currentWorkingDirectorySupplier, stdout, stderr);
JavaMetricsCliCommand rootCommand = new JavaMetricsCliCommand(stdout);
CommandLine commandLine = new CommandLine(rootCommand)
        .addSubcommand("analyze", analyzeCommand)
        .addSubcommand("validate", validateCommand);
```

New:
```java
AnalyzeCommand analyzeCommand = new AnalyzeCommand(analyzer, jsonWriter, currentWorkingDirectorySupplier, stdout, stderr);
ValidateCommand validateCommand = new ValidateCommand(analyzer, currentWorkingDirectorySupplier, stdout, stderr);
DetectCommand detectCommand = new DetectCommand(analyzer, currentWorkingDirectorySupplier, stdout, stderr);
JavaMetricsCliCommand rootCommand = new JavaMetricsCliCommand(stdout);
CommandLine commandLine = new CommandLine(rootCommand)
        .addSubcommand("analyze", analyzeCommand)
        .addSubcommand("validate", validateCommand)
        .addSubcommand("detect", detectCommand);
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :java-metrics-cli:test --tests "org.b333vv.metric.cli.DetectCommandTest" 2>&1`
Expected: All 4 tests pass

Run: `./gradlew :java-metrics-cli:test --tests "org.b333vv.metric.cli.CombinationDetectorTest" 2>&1`
Expected: All tests pass (including writer tests)

Run: `./gradlew :java-metrics-cli:test 2>&1`
Expected: All CLI tests pass (including existing `JavaMetricsCliApplicationTest`)

- [ ] **Step 6: Commit**

```bash
git add java-metrics-cli/src/main/java/org/b333vv/metric/cli/DetectCommand.java java-metrics-cli/src/main/java/org/b333vv/metric/cli/JavaMetricsCliApplication.java java-metrics-cli/src/test/java/org/b333vv/metric/cli/DetectCommandTest.java
git commit -m "feat: add detect command with CLI registration"
```

---

### Task 5: Full Build Verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew check build 2>&1`
Expected: BUILD SUCCESSFUL (all tests pass, all checks pass)

- [ ] **Step 2: Update WAL and commit**

Edit `docs/WAL.md` to reflect the new `detect` command completion. Replace the `## Last Action Completed` and `## Next Immediate Step` sections:

```markdown
## Last Action Completed
- [2026-05-15] Added detect subcommand with --class-rules and --package-rules
  - New command checks classes/packages against named metric rules
  - Outputs JSON with matched rules and entities
  - CombinationDefinition, CombinationDetector, DetectResultWriter

## Next Immediate Step
- Use the detect command in CI/CD pipelines
- Add method-level rules in the future
```

Then commit:
```bash
git add docs/WAL.md
git commit -m "docs: update WAL after detect command implementation"
```

