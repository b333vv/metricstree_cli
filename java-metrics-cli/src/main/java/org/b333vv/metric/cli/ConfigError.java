package org.b333vv.metric.cli;

/**
 * A configuration file that cannot mean what it says.
 *
 * <h2>Why this needs its own type</h2>
 * <p>Both the project config and the threshold profiles are user input, and every failure mode of
 * them is a usage or environment error: exit 2. The application maps an uncaught
 * {@link IllegalArgumentException} to exit 1, because that code means "the analysis ran and something
 * went wrong while running" — a parse error in the analysed Java, a solver failure. Reporting
 * {@code failOn: [everything]} as "Analysis failed" with exit 1 tells a CI author that their change
 * broke the tool, when in fact they mistyped a config key and nothing was ever analysed.
 *
 * <p>It stays an {@link IllegalArgumentException} so every existing call site, and any caller that
 * already catches that type, keeps working unchanged. The distinction is in how it is <em>reported</em>,
 * not in what callers must do about it.
 */
class ConfigError extends IllegalArgumentException {

    ConfigError(String message) {
        super(message);
    }
}
