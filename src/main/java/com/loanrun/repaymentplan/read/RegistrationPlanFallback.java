package com.loanrun.repaymentplan.read;

/** Local V2 compatibility capability, not a new shared financial contract. */
public interface RegistrationPlanFallback {
    boolean registrationFallbackAllowed();
    com.loanrun.common.contract.FinanceModels.PlanSnapshot currentPlan(long userId,long loanId,java.time.LocalDate asOf);
}
