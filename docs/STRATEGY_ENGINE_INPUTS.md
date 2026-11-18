# Strategy engine inputs

The builder passes immutable existing terms, a common asOf date and a 1200-month horizon to the existing
RepaymentEngine. It only constructs fee-inclusive cash conditions. It does not calculate interest, fees,
principal reductions, payoff dates or feasibility from an invented schedule.

KEEP_PLANS uses configured monthly targets, preserving original saving goals and future effective/grace
months. Missing configured plans mean no additional target. Candidate types/budgets come from the pure
input policy. Required BULLET saving is reserved first. Priority strategies seed the first eligible target and
retain zero entries/priorities for later targets. PARALLEL seeds initial remaining-balance cash weights with
one-won remainders by ID. These are request conditions, not predicted principal repayments.

The engine must honor allocationRule: recalculate PARALLEL remaining-principal proportions per period,
cap actual principal and account for fees within cash, and move only existing extra cash after payoff.
It must not reinvest scheduled payments. This Team 1 behavior requires real integration verification;
no substitute engine or runtime successful fixture is supplied here.

Only the first month deducts actual extra cash and saving. Total used cash, reserves and remainingAllocatable
also cap the remaining first-month request. Future months restore repeated goals, without multiplying carry.
currentFundingAvailable is false when those current goals cannot be funded; the report adapter must not
recommend or allow applying such a candidate. Unknown/unfundable saving conditions produce no candidate.
This change does not select loan report scope, persist candidates, expose an endpoint or call the engine itself.
