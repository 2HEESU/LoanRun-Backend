package com.loanrun.risk.domain;

import com.loanrun.common.contract.FinanceModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** Internal facts for risk analysis. Loan arithmetic and projections are supplied by Team 1. */
public record RiskInputs(long userId, LocalDate asOf, YearMonth month, List<LoanTerms> loans,
    List<PlanSnapshot> plans, FinanceSnapshot finance, BudgetCapacity capacity, DueSummary due,
    Long monthlyTarget, Long requiredSaving, BigDecimal variableExposurePct) {
    public RiskInputs { loans=List.copyOf(loans);plans=List.copyOf(plans); }
}
