package org.b333vv.metric.cli;

import picocli.CommandLine;

/** Accepts the documented kebab-case spelling for the Markdown format. */
final class OutputFormatConverter implements CommandLine.ITypeConverter<OutputFormat> {
    @Override
    public OutputFormat convert(String value) {
        return OutputFormat.valueOf(value.replace('-', '_').toUpperCase(java.util.Locale.ROOT));
    }
}
