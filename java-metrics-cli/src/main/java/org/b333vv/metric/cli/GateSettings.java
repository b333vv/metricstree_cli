package org.b333vv.metric.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The {@code gate:} section of a project config, as the file stated it.
 *
 * <h2>Why this is a value and not four more fields on {@link ProjectConfig}</h2>
 * <p>{@code ProjectConfig} is a flat bag of every optional setting across four commands, and the gate
 * was already contributing four of them. Adding {@code mode}, {@code policy}, {@code enforcement}
 * and {@code analysis} to that list would give {@link GateCommand} a constructor-shaped dependency on
 * eleven nullable accessors, and — worse — would make "the gate section was absent" something the
 * type cannot express, because six absent values and an empty section look identical.
 *
 * <p>This type makes that distinction real: {@code null} means the section was absent and every gate
 * setting falls back; a present instance with empty growth and null {@code failOn} means the author
 * wrote something narrower. Both are reachable, neither is accidental.
 *
 * <h2>What is validated here and what is not</h2>
 * <p>Shape, key names, value types and ranges are all checked by {@link ProjectConfigLoader} while
 * reading, so a config that cannot mean what it says never becomes a {@code GateSettings}. What is
 * deliberately <em>not</em> resolved here is the interaction with the command: which finding types
 * may be selected, whether a profile may be combined with a policy, what a mode string means. Those
 * belong to {@link GateCommand}, which can name the command line in the error. The split is the
 * difference between "this file is malformed" and "this invocation is not allowed".
 *
 * <p>The {@code mode}, {@code policy}, {@code enforcement} and {@code analysis} fields are carried but
 * not yet acted on: ML-004, ML-014 and ML-019 own those behaviours, and a field that exists before its
 * behaviour would be a silently ignored setting.
 *
 * @param growth      per-metric allowed growth between revisions; {@code null} means "use the default"
 * @param failOn      finding types that fail the gate; {@code null} means "all selectable types"
 * @param mode        comparison mode ({@code worktree} / {@code staged} / {@code committed});
 *                    {@code null} until ML-004 wires it
 * @param policy      policy name ({@code legacy} / {@code maintainability}); {@code null} until ML-019
 * @param enforcement enforcement level; {@code null} until ML-019
 * @param analysis    analysis mode ({@code local} / {@code project}); {@code null} until ML-007
 * @param sourceRoots repository-relative-or-absolute source roots to analyse per revision, resolved
 *                    against the config file's own directory (ML-011)
 * @param classpath   classpath entries for symbol resolution in project scope, resolved the same way
 */
record GateSettings(
        Map<String, Double> growth,
        List<String> failOn,
        String mode,
        String policy,
        String enforcement,
        String analysis,
        List<Path> sourceRoots,
        List<Path> classpath) {

    static final GateSettings EMPTY =
            new GateSettings(null, null, null, null, null, null, List.of(), List.of());

    GateSettings {
        growth = growth == null ? null : Map.copyOf(growth);
        failOn = failOn == null ? null : List.copyOf(failOn);
        sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
        classpath = classpath == null ? List.of() : List.copyOf(classpath);
    }
}
