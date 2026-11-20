# Finance mutation journal hooks

Runtime recording is formalized with `V6__risk_history_and_assessments.sql` and enabled by default (`RISK_HISTORY_ENABLED=true`).
When explicitly disabled (`RISK_HISTORY_ENABLED=false`), no empty risk-history provider is registered.

Profile PUT, current-month budget PUT and income-entry POST capture own-budget planned income/expense
amounts before and after the mutation. The journal uses the financial mutation's revision under the same
user lock/transaction. Initial profile creation is a baseline; identical amounts do not produce a change.
Confirmed INPUT months remain unchanged when profile defaults change. An income idempotency replay
does not execute the mutation or append another history event.

Capture uses only Team 2 budget tables, with no loan engine or cyclic BudgetQueryPort call. Regular planned
income is expected income plus recorded entries; irregular planned income uses the same three observed
completed-month average/fallback rule as budget reads. Expenses include required/selected expenses and
general saving, consistently with planning cash B. This is a record of planned cash input changes, not an
invented historical risk score or an assertion of actual received income. The original income entries remain.

Failures in journal append roll back entries/expenses/revision/idempotency together. Existing history is never
updated/deleted. The risk-history schema is applied via Flyway `V6__risk_history_and_assessments.sql`.

Verification: finance-history HTTP tests cover baseline/change/replay/rollback and irregular planning-income
agreement with budget reads (4). Existing profile/monthly-write/income tests cover the unchanged API behavior.
Ledger/plan inputs are test-only mocks. This does not establish live source or deployment completion.
