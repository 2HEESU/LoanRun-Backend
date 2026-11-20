package com.loanrun.repaymentplan.domain;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.RepaymentEngine;
import com.loanrun.finance.engine.RepaymentDates;
import com.loanrun.repaymentplan.domain.PlanPerformanceCalculator.Planned;
import com.loanrun.repaymentplan.domain.PlanPerformanceCalculator.Scope;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Original plan schedule only; never substitute today's loan terms for a missing captured basis. */
public final class PlanScheduleProjection {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final RepaymentEngine engine;
    public PlanScheduleProjection(RepaymentEngine engine) { this.engine = Objects.requireNonNull(engine); }

    public enum Reason {
        AVAILABLE, NOT_CONFIGURED, NOT_EFFECTIVE, MISSING_BASIS, BASIS_TIME_UNCERTAIN,
        PRIOR_DUE_UNRESOLVED, FEE_UNCERTAIN, HORIZON_UNSUPPORTED
    }
    /** simulation may retain a gross comparison even when planned is unavailable due to unknown fees. */
    public record Projection(Reason reason, LocalDate basisDate, Planned planned, SimulationResult simulation) {}

    public Projection throughMonth(SavedPlanSource source, YearMonth throughMonth) {
        Objects.requireNonNull(source); Objects.requireNonNull(throughMonth);
        if (source.userId() <= 0 || source.loanId() <= 0 || source.version() < 1 || source.effectiveDate() == null
                || source.monthlyExtraCash() < 0 || source.monthlySaving() < 0
                || throughMonth.getYear() < 1 || throughMonth.getYear() > 9999)
            throw new IllegalArgumentException("Invalid plan schedule scope");
        if (!source.configured()) return unavailable(Reason.NOT_CONFIGURED);
        if (throughMonth.atEndOfMonth().isBefore(source.effectiveDate())) return unavailable(Reason.NOT_EFFECTIVE);
        var terms = source.basisTerms();
        if (terms == null || source.startPrincipal() == null) return unavailable(Reason.MISSING_BASIS);
        if (terms.userId() != source.userId() || terms.loanId() != source.loanId() || terms.outstanding() != source.startPrincipal()
                || terms.status() != LoanStatus.ACTIVE || source.startPrincipal() < 0 || source.recordedAt() == null
                || terms.snapshotDate() == null || terms.maturityDate() == null || terms.nextDueDate() == null
                || terms.method() == null || source.effectiveDate().isAfter(terms.maturityDate())
                || terms.method() == RepaymentMethod.BULLET && source.monthlyExtraCash() != 0
                || terms.method() != RepaymentMethod.BULLET && source.monthlySaving() != 0)
            throw new IllegalArgumentException("Plan schedule basis mismatch");
        var basisDate = source.recordedAt().atZone(KST).toLocalDate();
        if (basisDate.isAfter(source.effectiveDate()) || terms.snapshotDate().isAfter(basisDate))
            return unavailable(Reason.BASIS_TIME_UNCERTAIN);
        // A next-due date alone cannot establish whether an earlier cycle was paid or still owed.
        if (terms.nextDueDate().isBefore(source.effectiveDate())) return unavailable(Reason.PRIOR_DUE_UNRESOLVED);
        long contractMonths = ChronoUnit.MONTHS.between(YearMonth.from(basisDate), YearMonth.from(terms.maturityDate())) + 1;
        if (contractMonths < 1 || contractMonths > 1200) return unavailable(Reason.HORIZON_UNSUPPORTED);
        var lastMonth = throughMonth.isBefore(YearMonth.from(terms.maturityDate())) ? throughMonth : YearMonth.from(terms.maturityDate());
        int horizon = Math.toIntExact(ChronoUnit.MONTHS.between(YearMonth.from(basisDate), lastMonth) + 1);
        Map<YearMonth, List<PlannedAmount>> amounts = new HashMap<>();
        boolean bullet = terms.method() == RepaymentMethod.BULLET;
        for (var date : RepaymentDates.remaining(terms, basisDate)) {
            var month = YearMonth.from(date);
            if (month.isAfter(lastMonth)) break;
            if (date.isBefore(source.effectiveDate())) return unavailable(Reason.PRIOR_DUE_UNRESOLVED);
            amounts.put(month, List.of(new PlannedAmount(source.loanId(), bullet ? AllocationType.REPAYMENT_SAVING : AllocationType.EXTRA_PAYMENT,
                bullet ? source.monthlySaving() : source.monthlyExtraCash(), null)));
        }
        var simulation = engine.simulate(new SimulationInput(List.of(terms), basisDate, horizon, amounts, Map.of(), null, "KEEP_PLANS"));
        if (simulation.rows().stream().anyMatch(row -> row.extraPrincipal() > 0 && row.fee() == null))
            return new Projection(Reason.FEE_UNCERTAIN, basisDate, null, simulation);
        var scope = new Scope(source.userId(), source.loanId(), source.version(), source.effectiveDate(), throughMonth.atEndOfMonth());
        return new Projection(Reason.AVAILABLE, basisDate, new Planned(scope, simulation.rows()), simulation);
    }

    private static Projection unavailable(Reason reason) { return new Projection(reason, null, null, null); }
}
