# Payment observation evidence — Issue #104

The user confirmed the criterion on 2026-11-19 KST: observed is true only when the month's performance
recording is complete or actual zero is confirmed. An unrecorded month must not be treated as zero.
The current V2 schema has payment/dues/savings facts, but no monthly completion/zero-confirmation fact.
One payment, a due marked PAID or an individual missed-due confirmation is not substituted for that fact.

PaymentObservationQueryService reads a user/optional-loan/month-scoped payment snapshot, obtains optional
explicit evidence through the local PaymentObservationSource interface, and evaluates the pure policy.
This is an internal payment-domain read model, not a new HTTP API or a common-contract change.

| Evidence for the current scoped snapshot | observed | actuals | dataStatus |
| --- | --- | --- | --- |
| None, even with some recorded payments | false | null | INSUFFICIENT |
| None, with no payment rows | false | null | INSUFFICIENT |
| Explicit recording completion | true | Exact recorded totals, including zero components | RECORDED |
| Explicit actual-zero confirmation and no payment rows | true | Actual zero totals | RECORDED |
| Stale confirmation, future month/evidence, or zero claim contradicting payment rows | false | null | INSUFFICIENT |

The raw source.recorded totals remain available separately when actuals is null. This preserves known
partial ledger facts without promoting them to completed monthly performance. Zero cash outflow funded
by savings is not zero payment performance: a positive recorded payment with cashPaid=0 cannot use a
ZERO_CONFIRMED claim, but valid recording completion can confirm its actual principal and zero cash.
Explicit completion of an empty ledger also confirms actual zero; absence of completion never does.

A source confirmation binds the exact owner, loan/household selection, month, source fingerprint, kind
and confirmation time. Another owner's/loan's/month's evidence cannot be used. Wrong-scope or malformed
source evidence fails with SERVICE_UNAVAILABLE. Ordinary stale/conflicting evidence is unobserved.
The current KST month is captured from one injected Clock instant. Future months are never observed;
the report layer still separately filters to completed calendar months for six-month averages.

Issue #134 adds observeAt with a bounded captured instant for multi-month composition. Evidence arriving after
that instant remains unknown. A confirmation made before its scoped month began cannot become valid later.
This is not a historical-row reconstruction API. See [plan observation inputs](PLAN_OBSERVATION_QUERY.md).

The snapshot uses the real payment aggregate plus canonical financial rows in one read-only
REPEATABLE_READ transaction. SHA-256 includes a versioned owner/selection/month prefix, then each
payment's ID, loan, actual date, type and amount/principal/extra/interest/fee/savings-used fields in ID
order. Replacing a record with identical amounts, or changing its composition without changing the
gross amount, invalidates old evidence. Other months and nonfinancial memo-only edits do not. Global
user revision is not used as a blanket invalidator for unchanged historical payment months. If a caller
already owns a transaction, that caller owns isolation/locking guarantees.

The fingerprint binds payment-row contents; it does not itself prove user completion, contractual due
coverage, savings/income recording completion or authority to confirm anything. A future real source
must obtain committed, explicit recording-complete/zero evidence for the full requested scope, handle
its workflow's invalidation conditions, and read within the caller's database transaction. No client
JSON/header can directly supply this internal confirmation, and no proof writer/endpoint is introduced.

No default confirmation or runtime empty-source implementation is installed. With no real completion
source, the normal runtime returns observed=false and actuals=null while preserving raw source totals.
Only tests supply explicit evidence to validate true/zero paths. Completion evidence persistence/input
workflow is still unconnected; this feature does not claim that live users can now mark months complete.
LoanQueryPort, API #27's plan/ratio fields and actual budget/CSV/risk integration remain separate work.
There is no migration, dependency, environment variable, shared port or Team2 code change.

Tests cover absent evidence with empty/positive rows, explicit completion/zero, savings-funded zero
cash, source staleness/scope, future confirmation/KST boundaries, optional-provider behavior, real
PostgreSQL fingerprint changes and financial read-only behavior. Current public APIs are unchanged.
