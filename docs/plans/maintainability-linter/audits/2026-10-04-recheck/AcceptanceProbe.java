package org.b333vv.metric.cli;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.b333vv.metric.library.core.*;
import org.b333vv.metric.library.javaparser.*;
import org.b333vv.metric.model.metric.value.Value;

/** Additional deterministic probes for the implementation audit, outside production tests. */
public class AcceptanceProbe {
    public static void main(String[] argv) throws Exception {
        Path repo = Path.of(argv[0]);
        Path file = repo.resolve("Demo.java");
        var delegate = new JavaParserJavaMetricsAnalyzer();
        JavaMetricsAnalyzer mutating = request -> {
            var result = delegate.analyze(request);
            if (request.projectName().equals("gate-current")) {
                try { Files.writeString(file, "class Demo { void broken( }"); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
            return result;
        };
        var app = new JavaMetricsCliApplication(mutating, new MetricReportJsonWriter(), () -> repo);
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exit = app.run(new String[]{"gate", "--base", "HEAD", "--no-config", "--policy",
                "maintainability", "-o", repo.resolve("report.json").toString()}, stdout, stderr);
        System.out.println("source-mutation exit=" + exit + " stderr=" + stderr.toString().strip());

        // Mutation now correctly prevents a report. Generate an independent stable report for schema probes.
        String stable = "class Demo { void run(int x) { " + "if(x>0) x++; ".repeat(17) + "} }";
        Files.writeString(file, stable);
        var stableApp = new JavaMetricsCliApplication(delegate, new MetricReportJsonWriter(), () -> repo);
        stableApp.run(new String[]{"gate", "--base", "HEAD", "--no-config", "--policy",
                "maintainability", "-o", repo.resolve("report.json").toString()},
                new ByteArrayOutputStream(), new ByteArrayOutputStream());

        var rule = MaintainabilityRules.byId("MT-C001").orElseThrow();
        var key = EntityKey.ofClass("Demo.java", "Demo");
        var evaluator = new ClassRuleEvaluator();
        var base = evaluator.evaluate(rule, key,
                Map.of(MetricCode.WMC, Value.of(47), MetricCode.ATFD, Value.of(6), MetricCode.TCC, Value.of(0.3)),
                MetricRequirements.Scope.PROJECT_GLOBAL);
        var after = evaluator.evaluate(rule, key,
                Map.of(MetricCode.WMC, Value.of(67), MetricCode.ATFD, Value.of(6), MetricCode.TCC, Value.of(0.2)),
                MetricRequirements.Scope.PROJECT_GLOBAL);
        System.out.println("C001-WMC-plus20-TCC-decrease worsened=" +
                new FindingDeltaEvaluator().isSignificantlyWorse(rule, base, after));

        Files.writeString(file, "class Demo { void run(int x) { " + "if(x>0) x++; ".repeat(110) +
                "if(x>1){ if(x>2){ if(x>3){ if(x>4){ if(x>5){x++;} } } } } } }");
        var traced = delegate.analyze(new AnalysisRequest("trace", List.of(), List.of(new SourceUnit(file)),
                List.of(), AnalysisOptions.of(MetricSelection.of(MetricCode.CC, MetricCode.MND))
                .withContributionEvidence())).methods().get(0).evidence();
        System.out.println("trace-cap CC=" + traced.forMetric(MetricCode.CC).size() +
                " MND=" + traced.forMetric(MetricCode.MND).size() + " MND-omitted=" + traced.omitted(MetricCode.MND));
        System.out.println("MND-witness-max=" + traced.forMetric(MetricCode.MND).stream()
                .mapToInt(MetricContribution::amount).max().orElse(0) + " aggregate=5");

        Path first = repo.resolve("roles1.json"), second = repo.resolve("roles2.json");
        Files.writeString(first, "{\"maintainability\":{\"roles\":[{\"pathRegex\":\".*\",\"role\":\"production\"}]}}");
        Files.writeString(second, "{\"maintainability\":{\"roles\":[{\"pathRegex\":\".*\",\"role\":\"dto\"}]}}");
        System.out.println("role-classification-digests-equal=" +
                RuleConfigLoader.load(first).digest().equals(RuleConfigLoader.load(second).digest()));

        // A staged snapshot must also detect a change to the index during the run.
        Files.writeString(file, stable);
        new ProcessBuilder("git", "add", "Demo.java").directory(repo.toFile()).start().waitFor();
        JavaMetricsAnalyzer indexMutating = request -> {
            var result = delegate.analyze(request);
            if (request.projectName().equals("gate-current")) {
                try {
                    Files.writeString(file, "class Demo { void run(int x) {} }");
                    new ProcessBuilder("git", "add", "Demo.java").directory(repo.toFile())
                            .start().waitFor();
                } catch (IOException e) { throw new UncheckedIOException(e); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            return result;
        };
        var indexApp = new JavaMetricsCliApplication(indexMutating, new MetricReportJsonWriter(), () -> repo);
        var indexErrors = new ByteArrayOutputStream();
        int indexExit = indexApp.run(new String[]{"gate", "--base", "HEAD", "--mode", "staged",
                "--no-config", "--policy", "maintainability", "-o", repo.resolve("index-report.json").toString()},
                new ByteArrayOutputStream(), indexErrors);
        System.out.println("staged-index-mutation exit=" + indexExit + " stderr=" + indexErrors.toString().strip());

        // Required method input is absent; no analyzer diagnostic can substitute for that check.
        var method = new MethodReport("run(int)", "run", 1, new SourceLocation(file, 1, 1), Map.of());
        var cls = new ClassReport("Demo", "Demo", file, new SourceLocation(file, 1, 1), Map.of(), List.of(method));
        var missing = new MetricReport(new ProjectReport("missing", Map.of(),
                List.of(new PackageReport("", Map.of(), List.of(cls)))), List.of());
        JavaMetricsAnalyzer missingAnalyzer = request -> new MetricReport(missing.project(), List.of(),
                delegate.analyze(request).syntaxSupport());
        Path config = repo.resolve("missing.json");
        Files.writeString(config, "{\"maintainability\":{\"enabledRules\":[\"MT-M001\"],"
                + "\"rules\":{\"MT-M001\":{\"mode\":\"error\"}}}}");
        var missingApp = new JavaMetricsCliApplication(missingAnalyzer, new MetricReportJsonWriter(), () -> repo);
        int missingExit = missingApp.run(new String[]{"detect", "-s", file.toString(),
                "--policy", "maintainability", "--enforcement", "enforce", "--config", config.toString(),
                "-o", repo.resolve("missing-report.json").toString()},
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        System.out.println("required-method-metric-missing exit=" + missingExit + " report="
                + Files.readString(repo.resolve("missing-report.json")).replace('\n', ' '));

        var document = CliObjectMapper.readTree(Files.readString(repo.resolve("report.json")));
        System.out.println("schema-valid-report-errors=" + FindingSchema.v2().violations(document));
        // Isolate null type handling from the unrelated newly undeclared analysis field.
        ((com.fasterxml.jackson.databind.node.ObjectNode) document).remove("analysis");
        if (!document.path("findings").isEmpty()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) document.path("findings").get(0)).putNull("entityKey");
            System.out.println("schema-null-entityKey-errors=" + FindingSchema.v2().violations(document));
        }
    }
}
