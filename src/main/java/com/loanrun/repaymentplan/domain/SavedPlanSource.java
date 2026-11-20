package com.loanrun.repaymentplan.domain;

import com.loanrun.common.contract.FinanceModels.LoanTerms;
import com.loanrun.repaymentplan.domain.PlanVersion.*;
import java.time.*;

/** Persisted goals only. Null basis/inputs mean not captured, never current values substituted as history. */
public record SavedPlanSource(long userId,long loanId,long version,Origin origin,boolean configured,
    String mode,String preference,LocalDate effectiveDate,LocalDate targetDate,LocalDate targetPayoffDate,
    long monthlyExtraCash,long monthlySaving,Long startPrincipal,LoanTerms basisTerms,Inputs inputs,
    StrategySource strategy,Instant recordedAt) {
    public enum Origin { REGISTRATION,PLAN_VERSION }
}
