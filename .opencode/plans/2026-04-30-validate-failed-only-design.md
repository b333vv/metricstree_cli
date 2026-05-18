# Design: Failed-Only Flag for Validate Command

## Summary
Add a `--failed-only` flag to the `validate` subcommand that filters the output JSON to include only `MetricValidationResult` entries with `status: FAILED`. Summary counters (`passed`, `failed`) and overall `status` remain unchanged.

## Changes

### File: `ValidateCommand.java`

1. **Add CLI option** (after existing `--strict` option, ~line 66):
   ```java
   @Option(names = "--failed-only", description = "Include only FAILED metric results in output")
   private boolean failedOnly = false;
   ```

2. **Modify `writeReport()` method** (~line 170):
   - Before serializing, check `failedOnly` flag
   - If true, filter `result.results` to keep only entries where `status == ValidationStatus.FAILED`
   - Counters (`passed`, `failed`) and `status` are computed before filtering and remain unchanged

### Output JSON (with `--failed-only`)
```json
{
  "status": "FAILED",
  "results": [
    {"file": "Demo.java", "metric": "WMC", "value": 15.0, "expectedMin": 0.0, "expectedMax": 12.0, "status": "FAILED"}
  ],
  "passed": 42,
  "failed": 3
}
```

## Trade-offs Considered
- **Post-filtering vs separate DTO**: Chose post-filtering for simplicity (~5 lines) vs boilerplate of a new DTO class
- **Counters behavior**: Counters reflect full validation run, not filtered output, to preserve auditability

## Testing
- Add unit test for `--failed-only` flag behavior in `ValidateCommand`
- Verify output contains only FAILED results while counters remain accurate
