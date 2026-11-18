# Risk input assembly

`RiskInputService` assembles internal risk facts through the existing owner-scoped loan, plan and budget
query ports. It introduces no HTTP routes, schema or engine implementation. HTTP callers must check PRO and
validate selected loan IDs separately; the snapshot always describes the whole household.

Queries use one user/month in a read-only repeatable-read transaction. Past months use month end for both
loan and plan projections; current inputs use the injected KST Clock. When called inside another transaction,
the caller must retain that isolation. Historical correctness also requires the query-port implementations to
honor the supplied date; this adapter does not manufacture historical snapshots.

Recurring targets include installment extra cash and the greater of planned/required BULLET savings.
Grace-period extra targets begin on the grace end date. Unconfigured plans or unknown grace dates make
target totals unknown, while an empty active-loan set has known zero targets. Only active-loan plans count.
Variable exposure uses outstanding variable-rate principal divided by all active outstanding principal;
a zero denominator remains null. Original nullable actual income and budget facts are preserved.

The adapter performs no interest, schedule or payoff calculation and does not call an engine or an external
service. Missing query implementations fail with `503 SERVICE_UNAVAILABLE`; startup does not install runtime
fixtures. Immutable snapshots are inputs for subsequent risk/scenario API changes, not completed APIs.
