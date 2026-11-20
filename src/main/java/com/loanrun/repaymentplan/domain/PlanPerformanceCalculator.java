package com.loanrun.repaymentplan.domain;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.PlanPresentationPort.Track;
import com.loanrun.payment.domain.DueStateCalculator.Status;
import java.math.*;
import java.time.*;
import java.util.*;

/** Pure performance calculation. Supplied scopes must have independently verified complete coverage. */
public final class PlanPerformanceCalculator {
    private PlanPerformanceCalculator() {}

    public record Scope(long userId, long loanId, long version, LocalDate from, LocalDate through) {
        public Scope {
            Objects.requireNonNull(from); Objects.requireNonNull(through);
            if (userId <= 0 || loanId <= 0 || version < 1 || through.isBefore(from))
                throw new IllegalArgumentException("Invalid performance scope");
        }
    }
    /** Complete original plan schedule through month end; null Planned means unavailable historical basis. */
    public record Planned(Scope scope, List<SimulationRow> rows) {
        public Planned { Objects.requireNonNull(scope); rows = List.copyOf(rows); }
    }
    /** Null per metric means unconfirmed, never inferred from absence/presence of ledger entries. */
    public record Actuals(Scope scope, Long principal, Long extraPrincipal, Long saving) {
        public Actuals {
            Objects.requireNonNull(scope); requireAmounts(principal, extraPrincipal, saving);
            if (principal != null && extraPrincipal != null && extraPrincipal > principal)
                throw new IllegalArgumentException("Extra principal exceeds total principal");
        }
    }
    public record SavingBalance(long userId, long loanId, LocalDate asOf, Long principal, Long savings) {
        public SavingBalance { Objects.requireNonNull(asOf); requireAmounts(principal, savings); }
    }
    /** Verified reduction from this version's captured start balance, not merely from its effective date. */
    public record PrincipalActual(long userId, long loanId, long version, LocalDate asOf, Long paidPrincipal) {
        public PrincipalActual { Objects.requireNonNull(asOf); requireAmounts(paidPrincipal); }
    }
    public record Input(SavedPlanSource plan, RepaymentMethod method, LocalDate asOf, Planned planned,
        PrincipalActual principalActual, Actuals cumulative, Actuals currentMonth, SavingBalance savingBalance, Status regularDueStatus) {}
    public record Comparison(Long planned, Long actual, Long gap, BigDecimal achievementPct) {}
    public record Performance(BigDecimal plannedProgressPct, BigDecimal actualProgressPct,
        Comparison monthlyExtra, Comparison cumulativeExtra, Comparison monthlySaving, Comparison cumulativeSaving,
        BigDecimal savingProgressPct, Track track) {}

    public static Performance calculate(Input input) {
        Objects.requireNonNull(input); var plan = Objects.requireNonNull(input.plan());
        Objects.requireNonNull(input.method()); var asOf = Objects.requireNonNull(input.asOf());
        if (!plan.configured() || plan.userId() <= 0 || plan.loanId() <= 0 || plan.version() < 1
                || plan.effectiveDate() == null || plan.effectiveDate().isAfter(asOf))
            throw new IllegalArgumentException("No effective configured plan");
        requireAmounts(plan.startPrincipal());
        var month = YearMonth.from(asOf);
        var monthStart = month.atDay(1).isBefore(plan.effectiveDate()) ? plan.effectiveDate() : month.atDay(1);
        requireScope(input.cumulative(), new Scope(plan.userId(), plan.loanId(), plan.version(), plan.effectiveDate(), asOf));
        requireScope(input.currentMonth(), new Scope(plan.userId(), plan.loanId(), plan.version(), monthStart, asOf));
        requireSubset(input.cumulative(), input.currentMonth());
        boolean bullet = input.method() == RepaymentMethod.BULLET;
        requireMethod(input.cumulative(), bullet); requireMethod(input.currentMonth(), bullet);
        Long plannedPrincipal = null, dueExtra = null, monthlyExtra = null, dueSaving = null, monthlySaving = null;
        if (input.planned() != null) {
            var expected = new Scope(plan.userId(), plan.loanId(), plan.version(), plan.effectiveDate(), month.atEndOfMonth());
            if (!expected.equals(input.planned().scope())) throw new IllegalArgumentException("Plan coverage mismatch");
            long p = 0, allPrincipal = 0, de = 0, me = 0, ds = 0, ms = 0;
            Set<LocalDate> dates = new HashSet<>();
            for (var row : input.planned().rows()) {
                if (row.loanId() != plan.loanId() || row.date().isBefore(expected.from()) || row.date().isAfter(expected.through())
                        || !dates.add(row.date()) || row.scheduledPrincipal() < 0 || row.extraPrincipal() < 0 || row.saving() < 0
                        || bullet && row.extraPrincipal() != 0 || !bullet && row.saving() != 0)
                    throw new IllegalArgumentException("Invalid plan performance row");
                allPrincipal = Math.addExact(allPrincipal, Math.addExact(row.scheduledPrincipal(), row.extraPrincipal()));
                if (!row.date().isAfter(asOf)) {
                    p = Math.addExact(p, Math.addExact(row.scheduledPrincipal(), row.extraPrincipal()));
                    de = Math.addExact(de, row.extraPrincipal()); ds = Math.addExact(ds, row.saving());
                }
                if (YearMonth.from(row.date()).equals(month)) {
                    me = Math.addExact(me, row.extraPrincipal()); ms = Math.addExact(ms, row.saving());
                }
            }
            if (plan.startPrincipal() != null && allPrincipal > plan.startPrincipal())
                throw new IllegalArgumentException("Plan schedule exceeds start principal");
            plannedPrincipal = p; dueExtra = de; monthlyExtra = me; dueSaving = ds; monthlySaving = ms;
        }
        Long actualPrincipal = null;
        if (input.principalActual() != null) {
            var actual = input.principalActual();
            if (actual.userId() != plan.userId() || actual.loanId() != plan.loanId() || actual.version() != plan.version()
                    || !actual.asOf().equals(asOf)) throw new IllegalArgumentException("Principal basis scope mismatch");
            actualPrincipal = actual.paidPrincipal();
        }
        if (plan.startPrincipal() != null && (plannedPrincipal != null && plannedPrincipal > plan.startPrincipal()
                || actualPrincipal != null && actualPrincipal > plan.startPrincipal()))
            throw new IllegalArgumentException("Progress exceeds plan start principal");
        var empty = new Comparison(null, null, null, null);
        var extraMonth = bullet ? empty : compare(monthlyExtra, extra(input.currentMonth()));
        var extraDue = bullet ? empty : compare(dueExtra, extra(input.cumulative()));
        var savingMonth = bullet ? compare(monthlySaving, saving(input.currentMonth())) : empty;
        var savingDue = bullet ? compare(dueSaving, saving(input.cumulative())) : empty;
        BigDecimal savingProgress = null;
        if (input.savingBalance() != null) {
            var balance = input.savingBalance();
            if (balance.userId() != plan.userId() || balance.loanId() != plan.loanId() || !balance.asOf().equals(asOf))
                throw new IllegalArgumentException("Saving balance scope mismatch");
            if (bullet) savingProgress = percent(balance.savings(), balance.principal());
        }
        var comparison = bullet ? savingDue : extraDue;
        String regular = input.regularDueStatus() == null ? null : input.regularDueStatus().name();
        Track track;
        if (input.regularDueStatus() == Status.UNRECORDED) track = new Track("UNRECORDED", "INSUFFICIENT", regular);
        else if (comparison.gap() == null) track = new Track(null, "INSUFFICIENT", regular);
        else track = new Track(comparison.gap() < 0 ? "BEHIND" : comparison.gap() > 0 ? "AHEAD" : "ON_TRACK", "SUFFICIENT", regular);
        return new Performance(percent(plannedPrincipal, plan.startPrincipal()), percent(actualPrincipal, plan.startPrincipal()),
            extraMonth, extraDue, savingMonth, savingDue, savingProgress, track);
    }

    private static Comparison compare(Long planned, Long actual) {
        return new Comparison(planned, actual, planned == null || actual == null ? null : Math.subtractExact(actual, planned), percent(actual, planned));
    }
    private static BigDecimal percent(Long numerator, Long denominator) {
        return numerator == null || denominator == null || denominator == 0 ? null : BigDecimal.valueOf(numerator)
            .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }
    private static void requireAmounts(Long... amounts) {
        for (var amount : amounts) if (amount != null && amount < 0) throw new IllegalArgumentException("Negative performance amount");
    }
    private static void requireScope(Actuals actual, Scope expected) {
        if (actual != null && !expected.equals(actual.scope())) throw new IllegalArgumentException("Actual performance scope mismatch");
    }
    private static void requireSubset(Actuals cumulative, Actuals monthly) {
        if (cumulative == null || monthly == null) return;
        if (cumulative.scope().equals(monthly.scope()) && (different(monthly.principal(), cumulative.principal())
                || different(monthly.extraPrincipal(), cumulative.extraPrincipal()) || different(monthly.saving(), cumulative.saving())))
            throw new IllegalArgumentException("Same-period actuals disagree");
        if (exceeds(monthly.principal(), cumulative.principal()) || exceeds(monthly.extraPrincipal(), cumulative.extraPrincipal())
                || exceeds(monthly.saving(), cumulative.saving())) throw new IllegalArgumentException("Month exceeds cumulative actuals");
    }
    private static boolean exceeds(Long part, Long total) { return part != null && total != null && part > total; }
    private static boolean different(Long first, Long second) { return first != null && second != null && !first.equals(second); }
    private static void requireMethod(Actuals actual, boolean bullet) {
        if (actual != null && (bullet && actual.extraPrincipal() != null && actual.extraPrincipal() != 0
                || !bullet && actual.saving() != null && actual.saving() != 0)) throw new IllegalArgumentException("Actual metric does not match method");
    }
    private static Long extra(Actuals a) { return a == null ? null : a.extraPrincipal(); }
    private static Long saving(Actuals a) { return a == null ? null : a.saving(); }
}
