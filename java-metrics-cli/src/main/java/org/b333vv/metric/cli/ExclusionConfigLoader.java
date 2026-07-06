package org.b333vv.metric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.b333vv.metric.library.core.ExclusionConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

final class ExclusionConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    private ExclusionConfigLoader() {
    }

    static ExclusionConfig load(Path configFile) {
        if (!Files.exists(configFile)) {
            throw new IllegalArgumentException(
                    "Error: Exclusions file not found at " + configFile.toAbsolutePath().normalize() +
                    ". Run without --exclude-file or provide a valid path.");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(configFile.toFile());
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Error: Failed to parse exclusions file " + configFile.toAbsolutePath().normalize() +
                    ": " + e.getMessage());
        }

        if (root == null || root.isEmpty()) {
            return ExclusionConfig.empty();
        }

        JsonNode exclusionsNode = root.get("exclusions");
        if (exclusionsNode == null || exclusionsNode.isEmpty()) {
            return ExclusionConfig.empty();
        }

        List<String> patterns = new ArrayList<>();

        JsonNode packagesNode = exclusionsNode.get("packages");
        if (packagesNode != null && packagesNode.isArray()) {
            for (JsonNode patternNode : packagesNode) {
                String pattern = patternNode.asText();
                if (!pattern.isEmpty()) {
                    patterns.add(pattern);
                }
            }
        }

        JsonNode classesNode = exclusionsNode.get("classes");
        if (classesNode != null && classesNode.isArray()) {
            for (JsonNode patternNode : classesNode) {
                String pattern = patternNode.asText();
                if (!pattern.isEmpty()) {
                    patterns.add(pattern);
                }
            }
        }

        if (patterns.isEmpty()) {
            return ExclusionConfig.empty();
        }

        try {
            return ExclusionConfig.of(patterns);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "Error parsing regex in " + configFile.toAbsolutePath().normalize() +
                    ": \"" + e.getPattern() + "\" - " + e.getDescription(), e);
        }
    }
}
