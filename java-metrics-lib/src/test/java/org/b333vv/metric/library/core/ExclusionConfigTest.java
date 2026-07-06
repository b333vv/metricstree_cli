package org.b333vv.metric.library.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.*;

class ExclusionConfigTest {

    @Test
    void emptyConfigShouldNeverExclude() {
        ExclusionConfig config = ExclusionConfig.empty();
        assertFalse(config.isExcluded("com.example.MyClass"));
        assertFalse(config.isExcluded(""));
        assertFalse(config.isExcluded(null));
    }

    @Test
    void emptyPatternsListShouldNeverExclude() {
        ExclusionConfig config = ExclusionConfig.of(List.of());
        assertFalse(config.isExcluded("com.example.MyClass"));
    }

    @Test
    void nullPatternsListShouldFallbackToEmpty() {
        ExclusionConfig config = ExclusionConfig.of(null);
        assertTrue(config.isEmpty());
    }

    @Test
    void singlePatternShouldMatchByFindSemantics() {
        ExclusionConfig config = ExclusionConfig.of(List.of(".*Test$"));
        assertTrue(config.isExcluded("com.example.MyTest"));
        assertTrue(config.isExcluded("com.example.SomeTest"));
        assertFalse(config.isExcluded("com.example.Tester"));
        assertFalse(config.isExcluded("com.example.TestHelper"));
    }

    @Test
    void findSemanticsShouldMatchSubstring() {
        ExclusionConfig config = ExclusionConfig.of(List.of("Test"));
        assertTrue(config.isExcluded("com.example.SomeTest"));
        assertTrue(config.isExcluded("com.example.TestHelper"));
        assertTrue(config.isExcluded("com.example.MyTestClass"));
    }

    @Test
    void packagePatternShouldMatchDotNotation() {
        ExclusionConfig config = ExclusionConfig.of(List.of("^com\\.mycompany\\.project\\.generated\\..*"));
        assertTrue(config.isExcluded("com.mycompany.project.generated.Foo"));
        assertTrue(config.isExcluded("com.mycompany.project.generated.bar.Baz"));
        assertFalse(config.isExcluded("com.mycompany.project.services.Foo"));
    }

    @Test
    void multiplePatternsShouldUseOrLogic() {
        ExclusionConfig config = ExclusionConfig.of(List.of(".*Test$", ".*Entity$"));
        assertTrue(config.isExcluded("com.example.UserTest"));
        assertTrue(config.isExcluded("com.example.OrderEntity"));
        assertFalse(config.isExcluded("com.example.UserService"));
    }

    @Test
    void invalidPatternShouldThrowAtConstruction() {
        assertThrows(PatternSyntaxException.class,
                () -> ExclusionConfig.of(List.of("(unclosed[pattern")));
    }

    @Test
    void emptyShouldReturnTrueForEmptyOrNoPatterns() {
        assertTrue(ExclusionConfig.empty().isEmpty());
        assertTrue(ExclusionConfig.of(List.of()).isEmpty());
        assertTrue(ExclusionConfig.of(null).isEmpty());
        assertFalse(ExclusionConfig.of(List.of(".*Test$")).isEmpty());
    }

    @Test
    void nullOrEmptyFqcnShouldNotBeExcluded() {
        ExclusionConfig config = ExclusionConfig.of(List.of(".*"));
        assertFalse(config.isExcluded(null));
        assertFalse(config.isExcluded(""));
    }
}
