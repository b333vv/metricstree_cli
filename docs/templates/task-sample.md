## TASK-042: Add CSV report export

### Goal
The user should be able to download a sales report in CSV format.

### User value
The report can be opened in Excel/Google Sheets without manually copying data.

### Scope
- Add an export button to the reports page.
- Implement the `/api/reports/{id}/export.csv` endpoint.
- Add tests for CSV format.

### Out of scope
- Do not change the sales model.
- Do not add Excel export.
- Do not rewrite report frontend components.

### Acceptance criteria
- For an existing report, a CSV with correct headers is returned.
- An empty report returns only headers.
- Missing permissions return a 403.
- All new and modified tests pass.

### Verification commands
- `make smoke`
- `make test`
- `make lint`
- `make typecheck`

### Risks
- Potentially large data sets.
- N+1 queries must be avoided.

### Definition of Done
- Code passes linting and type checking.
- Tests are green.
- Documentation is updated if the API changes.
- PR includes a description of changes and test evidence.