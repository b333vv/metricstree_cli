# Ordered task index

Execute one task at a time. All tasks start TODO; update status only with evidence.
Dependencies are technical prerequisites. Milestone boundaries and external evidence follow
[the master plan](../README.md). Numeric order is the recommended execution order.

| Task | Description | Dependencies | Status |
| --- | --- | --- | --- |
| [ML-001](ML-001.md) | Repair and validate threshold bounds | — | DONE |
| [ML-002](ML-002.md) | Validate gate configuration and fix profile plumbing | ML-001 | DONE |
| [ML-003](ML-003.md) | Create a NUL-safe read-only Git access layer | ML-002 | DONE |
| [ML-004](ML-004.md) | Resolve explicit comparison modes and one merge base | ML-003 | DONE |
| [ML-005](ML-005.md) | Materialize immutable source snapshots with logical paths | ML-004 | DONE |
| [ML-006](ML-006.md) | Wire snapshot comparison into the existing gate | ML-005 | DONE |
| [ML-007](ML-007.md) | Select safe local metrics and declare analysis requirements | ML-006 | DONE |
| [ML-008](ML-008.md) | Make incomplete evaluation visible in every gate verdict | ML-007 | DONE |
| [ML-009](ML-009.md) | Repair existing agent detection evidence and misleading text | ML-001 | DONE |
| [ML-010](ML-010.md) | Add ordered analysis and bounded reproducibility checks | ML-007, ML-008 | DONE |
| [ML-011](ML-011.md) | Analyze full per-revision semantic context in project mode | ML-008, ML-010 | DONE |
| [ML-012](ML-012.md) | Document metric variants and qualify rule inputs | ML-007, ML-010, ML-011 | DONE |
| [ML-013](ML-013.md) | Introduce immutable findings and stable entity identity | ML-008, ML-012 | DONE |
| [ML-014](ML-014.md) | Load a versioned rule catalog and strict policy overrides | ML-002, ML-013 | TODO |
| [ML-015](ML-015.md) | Evaluate method rules and add legacy method-rule inputs | ML-014 | TODO |
| [ML-016](ML-016.md) | Evaluate class rules with conservative evidence status | ML-014, ML-015 | TODO |
| [ML-017](ML-017.md) | Apply explicit code roles and stable exclusion semantics | ML-016 | TODO |
| [ML-018](ML-018.md) | Compare finding lifecycles across snapshots | ML-016, ML-017 | TODO |
| [ML-019](ML-019.md) | Wire the opt-in maintainability policy into gate and detect | ML-018 | TODO |
| [ML-020](ML-020.md) | Freeze version 2 JSON and logical source locations | ML-019 | TODO |
| [ML-021](ML-021.md) | Render actionable agent Markdown and HTML from findings | ML-020 | TODO |
| [ML-022](ML-022.md) | Support finding-based SARIF including incomplete runs | ML-020, ML-021 | TODO |
| [ML-023](ML-023.md) | Attach bounded complexity contribution traces | ML-021 | TODO |
| [ML-024](ML-024.md) | Add narrow suppressions with rationale and expiry | ML-020, ML-021 | TODO |
| [ML-025](ML-025.md) | Add a versioned finding baseline and cumulative regression checks | ML-024, ML-018 | TODO |
| [ML-026](ML-026.md) | Unify deduplication, ranking and summary accounting | ML-022, ML-023, ML-024, ML-025 | TODO |
| [ML-027](ML-027.md) | Verify the full edit-check-fix loop and report compatibility | ML-026 | TODO |
| [ML-028](ML-028.md) | Build versioned ready-to-run artifacts and release workflow | ML-027 | TODO |
| [ML-029](ML-029.md) | Make the GitHub Action work in an unrelated consumer repository | ML-028 | TODO |
| [ML-030](ML-030.md) | Publish a coherent local-agent-CI onboarding guide | ML-028, ML-029 | TODO |
| [ML-031](ML-031.md) | Record reproducible local and project performance baselines | ML-027, ML-028 | TODO |
| [ML-032](ML-032.md) | Build a labeled evaluation corpus and comparison harness | ML-027, ML-028, ML-031 | TODO |
| [ML-033](ML-033.md) | Run an advisory maintainer pilot and record actual feedback | ML-030, ML-032 | TODO |
| [ML-034](ML-034.md) | Calibrate rules and make a versioned default-policy decision | ML-012, ML-032, ML-033 | TODO |
| [ML-035](ML-035.md) | Capture typed dependency edges with source evidence | ML-011, ML-012, ML-027 | TODO |
| [ML-036](ML-036.md) | Detect new package cycle-closing edges with witness paths | ML-035 | TODO |
| [ML-037](ML-037.md) | Enforce explicit forbidden dependency pairs | ML-035 | TODO |
| [ML-038](ML-038.md) | Integrate structural findings with affected-graph comparisons | ML-036, ML-037, ML-026 | TODO |
| [ML-039](ML-039.md) | Add optional history-based prioritization without changing verdicts | ML-038 | TODO |
| [ML-040](ML-040.md) | Cache immutable analysis snapshots with complete invalidation | ML-031, ML-038 | TODO |
