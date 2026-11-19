package com.loanrun.repaymentplan.domain;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.finance.engine.DefaultRepaymentEngine;
import com.loanrun.finance.engine.RepaymentDates;
import com.loanrun.repaymentplan.domain.PlanProjectionBasisPolicy.*;
import java.time.*;
import java.util.*;

/** Pure individual projection from captured terms and independently verified observations. */
public final class PlanPayoffProjection {
    private final DefaultRepaymentEngine engine;

    public PlanPayoffProjection(DefaultRepaymentEngine engine) { this.engine = Objects.requireNonNull(engine); }

    /** Null simulation means the monthly input is unknown, not a zero-payment scenario. */
    public record Projection(Basis basis, SimulationResult simulation) {}

    public Projection project(LoanTerms terms, LocalDate registeredOn, LocalDate asOf, int horizonMonths,
            SavedPlanSource fallback, List<Observation> observations) {
        Objects.requireNonNull(terms);
        if (terms.status() != LoanStatus.ACTIVE || terms.outstanding() <= 0)
            throw new IllegalArgumentException("Projection requires an active outstanding loan");
        if (fallback != null && (fallback.effectiveDate() == null || fallback.effectiveDate().isAfter(asOf)))
            throw new IllegalArgumentException("Fallback plan is not effective at projection date");
        // Validate the requested horizon even when the monthly basis is unknown.
        new SimulationInput(List.of(terms), asOf, horizonMonths, Map.of(), Map.of(), null, "KEEP_PLANS");
        var basis = PlanProjectionBasisPolicy.select(terms.userId(), terms.loanId(), terms.method(),
            registeredOn, asOf, fallback, observations);
        if (basis.source() == Source.INSUFFICIENT) return new Projection(basis, null);
        Map<YearMonth, List<PlannedAmount>> cash = new HashMap<>();
        Map<YearMonth, Map<Long, Long>> principal = new HashMap<>();
        var endMonth = YearMonth.from(asOf).plusMonths(horizonMonths);
        for (var date : RepaymentDates.remaining(terms, asOf)) {
            var month = YearMonth.from(date);
            if (!month.isBefore(endMonth)) break;
            if (basis.amountKind() == AmountKind.EXTRA_PRINCIPAL)
                principal.put(month, Map.of(terms.loanId(), basis.monthlyAmount()));
            else cash.put(month, List.of(new PlannedAmount(terms.loanId(),
                basis.amountKind() == AmountKind.SAVING_ADDITION ? AllocationType.REPAYMENT_SAVING : AllocationType.EXTRA_PAYMENT,
                basis.monthlyAmount(), null)));
        }
        var input = new SimulationInput(List.of(terms), asOf, horizonMonths, cash, Map.of(), null, "KEEP_PLANS");
        var result = basis.amountKind() == AmountKind.EXTRA_PRINCIPAL
            ? engine.simulateWithExtraPrincipal(input, principal) : engine.simulate(input);
        return new Projection(basis, result);
    }
}
