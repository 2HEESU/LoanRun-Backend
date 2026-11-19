# Actual payment aggregates — Issue #102

PaymentActualsRepository/PaymentActualsQueryService read exact owner-scoped V2 payment facts by actual
paid_at date. aggregate returns full filtered totals; monthly returns one immutable row per actual
paid_at month and loan, sorted month then loan ID. Ranges include both date boundaries. An optional
foreign/missing loan filter returns LOAN_NOT_FOUND; PAID/DELETED loan history remains included.

Totals separate regular principal, extra principal, interest, fee, savings used and recorded amount.
amount = regular principal + extra principal + interest + fee. cashPaid = amount - savingsUsed, because
spending an existing repayment asset is not another cash expense. Savings never fund interest/fees.
Initial savings and repayment_savings additions are not payment records and do not enter these totals.
One positive payment fully funded by savings has cashPaid=0 but paymentCount=1, unlike an empty query.

Counts preserve three distinct facts: total payments, REGULAR/EXTRA type counts, and payments with a
positive extra principal component. A REGULAR installment payment can contain confirmed extra principal;
its payment type is not rewritten. No allocation joins are used, so multi-due payments are counted once.
All amount sums are exact long values; unrepresentable totals fail with SERVICE_UNAVAILABLE rather than
wrapping or silently producing partial sums. SQL binds every owner/filter value.

API #24 reuses this aggregate and the same bound predicates as its paged detail selection, under the
existing read-only REPEATABLE_READ transaction. Its public six-field totals and result shape are unchanged.
The internal cash/count fields are not additional API response fields. Page boundaries do not affect
aggregate scope. Standalone queries are read-only; an existing caller owns its transaction guarantees.

Missing months are not emitted as manufactured zero months. An empty aggregate has zero stored-record
sums/count, which does not assert an observed or confirmed zero-performance month. This layer neither
sets observed nor builds PaymentSummary/DueSummary/LoanQueryPort. Complete expected-due coverage and
monthly observation rules remain necessary before those projections are connected.

## Supported funding combinations checked before choosing any priority

The API #25/#29 specifications restrict savings use to BULLET principal. The repayment-method rules and
current engine give BULLET zero regular principal before maturity, and the remaining principal at
maturity. Early actual principal repayment is extra; a REGULAR interest payment may include a confirmed
extra component while its regular principal remains zero. At maturity the regular obligation consumes
the remaining principal: oldest-required-first fulfillment and no principal overpayment leave no extra
principal after it is fully met. For installment loans regular and extra principal may coexist, but
repayment savings cannot be used. A separate savings priority between two positive principal components
is therefore unnecessary for the normal supported flow; it is not being added as a product policy.

The current generic V2 columns alone can store both components with savings use. Payment write APIs
#25/#29 are not yet implemented, so there is no claim that their runtime validator already rejects every
unsupported combination. Existing synthetic read fixtures preserve database-shaped facts and are not
evidence that such input is a supported service feature. New aggregate examples separate BULLET early
EXTRA/maturity REGULAR payments, and use zero savings for mixed installment payments. Future write/replay
validation must enforce method, due-component and outstanding-principal consistency. Ambiguous/corrupt
stored combinations must not be resolved by silently inventing a funding priority in a future adapter.

No savings priority policy is introduced for an unreachable normal-flow combination.
Monthly observed status now requires explicit recording completion or actual-zero evidence (see PAYMENT_OBSERVATION.md).
This raw component does not infer either condition
for exact stored totals or cashPaid. No public API, common interface, migration, environment/dependency,
Team 2 code, actual DB or deployment changes are included.
