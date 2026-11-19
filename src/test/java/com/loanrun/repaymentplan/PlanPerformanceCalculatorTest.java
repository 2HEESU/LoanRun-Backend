package com.loanrun.repaymentplan;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.finance.engine.DefaultRepaymentEngine;
import com.loanrun.payment.domain.DueStateCalculator.Status;
import com.loanrun.repaymentplan.domain.*;
import com.loanrun.repaymentplan.domain.PlanPerformanceCalculator.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PlanPerformanceCalculatorTest {
    static final LocalDate START = LocalDate.of(2026, 9, 25), AS_OF = LocalDate.of(2026, 11, 19);
    SavedPlanSource plan(Long principal) {
        return new SavedPlanSource(7, 1, 2, SavedPlanSource.Origin.PLAN_VERSION, true, "EXTRA_AMOUNT", null,
            START, null, LocalDate.of(2027, 6, 25), 55, 0, principal, null, null, null,
            Instant.parse("2026-09-25T00:00:00Z"));
    }
    Scope scope(LocalDate from, LocalDate through) { return new Scope(7, 1, 2, from, through); }
    Actuals cumulative(Long principal, Long extra, Long saving) { return new Actuals(scope(START, AS_OF), principal, extra, saving); }
    Actuals monthly(Long principal, Long extra, Long saving) { return new Actuals(scope(AS_OF.withDayOfMonth(1), AS_OF), principal, extra, saving); }
    SimulationRow row(LocalDate date, long principal, long extra, long saving) {
        return new SimulationRow(date, 1, principal, 10, 5L, extra, extra + 5, saving, 0, 0);
    }
    Planned planned(List<SimulationRow> rows) { return new Planned(scope(START, LocalDate.of(2026, 11, 30)), rows); }
    Planned installment() { return planned(List.of(row(START, 100, 50, 0), row(START.plusMonths(1), 100, 50, 0), row(START.plusMonths(2), 100, 50, 0))); }
    PrincipalActual principal(Long amount) { return new PrincipalActual(7, 1, 2, AS_OF, amount); }
    Performance evaluate(Planned planned, Actuals cumulative, Actuals monthly, Status status) {
        return PlanPerformanceCalculator.calculate(new Input(plan(1000L), RepaymentMethod.EQUAL_PRINCIPAL, AS_OF,
            planned, cumulative == null ? null : principal(cumulative.principal()), cumulative, monthly, null, status));
    }

    @Test void runnerUsesPlanStartPrincipalAndTrackUsesDueExtraRatherThanTotalPrincipal() {
        var result = evaluate(installment(), cumulative(350L, 150L, 0L), monthly(0L, 0L, 0L), Status.PAID);
        assertThat(result.plannedProgressPct()).isEqualByComparingTo("30.00");
        assertThat(result.actualProgressPct()).isEqualByComparingTo("35.00");
        assertThat(result.cumulativeExtra().planned()).isEqualTo(100);
        assertThat(result.cumulativeExtra().gap()).isEqualTo(50);
        assertThat(result.cumulativeExtra().achievementPct()).isEqualByComparingTo("150.00");
        assertThat(result.track().status()).isEqualTo("AHEAD");
        // Regular principal ahead alone does not make the extra target ahead.
        assertThat(evaluate(installment(), cumulative(350L, 100L, 0L), null, Status.PAID).track().status()).isEqualTo("ON_TRACK");
    }
    @Test void futureMonthlyTargetIsDisplayedButExcludedFromCumulativeShortfall() {
        var result = evaluate(installment(), cumulative(300L, 100L, 0L), monthly(0L, 0L, 0L), Status.UPCOMING);
        assertThat(result.monthlyExtra().planned()).isEqualTo(50); assertThat(result.monthlyExtra().gap()).isEqualTo(-50);
        assertThat(result.cumulativeExtra().gap()).isZero(); assertThat(result.track().status()).isEqualTo("ON_TRACK");
        assertThat(result.track().regularDueStatus()).isEqualTo("UPCOMING");
    }
    @Test void targetBecomesDueOnItsDateWithoutChangingStoredRows() {
        var date = LocalDate.of(2026, 11, 25);
        var actual = new Actuals(scope(START, date), 300L, 100L, 0L);
        var result = PlanPerformanceCalculator.calculate(new Input(plan(1000L), RepaymentMethod.EQUAL_PRINCIPAL, date,
            installment(), null, actual, null, null, Status.UPCOMING));
        assertThat(result.cumulativeExtra().planned()).isEqualTo(150); assertThat(result.cumulativeExtra().gap()).isEqualTo(-50);
        assertThat(result.track().status()).isEqualTo("BEHIND"); assertThat(result.plannedProgressPct()).isEqualByComparingTo("45.00");
    }
    @Test void oneWonDifferenceIsNotRoundedAwayForTrackClassification() {
        assertThat(evaluate(installment(), cumulative(299L, 99L, 0L), null, Status.PAID).track().status()).isEqualTo("BEHIND");
        assertThat(evaluate(installment(), cumulative(301L, 101L, 0L), null, Status.PAID).track().status()).isEqualTo("AHEAD");
    }
    @Test void regularUnrecordedHasSeparatePriorityEvenWhenExtraGoalIsExceeded() {
        var result = evaluate(installment(), cumulative(350L, 150L, 0L), null, Status.UNRECORDED);
        assertThat(result.track().status()).isEqualTo("UNRECORDED"); assertThat(result.track().dataStatus()).isEqualTo("INSUFFICIENT");
        assertThat(result.cumulativeExtra().gap()).isEqualTo(50);
    }
    @Test void confirmedUnpaidAndPartialAreNotRelabeledAsUnrecordedOrExtraShortfall() {
        for (var status : List.of(Status.UNPAID, Status.PARTIAL)) {
            var result = evaluate(installment(), cumulative(100L, 100L, 0L), null, status);
            assertThat(result.track().status()).isEqualTo("ON_TRACK"); assertThat(result.track().regularDueStatus()).isEqualTo(status.name());
        }
    }
    @Test void unknownActualIsNotAConfirmedZeroOrAutomaticBehind() {
        var result = evaluate(installment(), cumulative(null, null, null), null, Status.PAID);
        assertThat(result.actualProgressPct()).isNull(); assertThat(result.cumulativeExtra().actual()).isNull();
        assertThat(result.cumulativeExtra().gap()).isNull(); assertThat(result.track().status()).isNull();
        assertThat(result.track().dataStatus()).isEqualTo("INSUFFICIENT");
        assertThat(evaluate(installment(), cumulative(0L, 0L, 0L), null, Status.PAID).track().status()).isEqualTo("BEHIND");
    }
    @Test void missingHistoricalPlanDoesNotGetReplacedByCurrentGoalOrCurrentBalance() {
        var result = evaluate(null, cumulative(300L, 100L, 0L), monthly(0L, 0L, 0L), null);
        assertThat(result.plannedProgressPct()).isNull(); assertThat(result.actualProgressPct()).isEqualByComparingTo("30.00");
        assertThat(result.cumulativeExtra().planned()).isNull(); assertThat(result.cumulativeExtra().gap()).isNull();
        assertThat(result.track().status()).isNull();
    }
    @Test void missingV2StartPrincipalLeavesBothRunnerRatiosUnknownWithoutLosingKnownComparisons() {
        var result = PlanPerformanceCalculator.calculate(new Input(plan(null), RepaymentMethod.EQUAL_PRINCIPAL, AS_OF,
            installment(), principal(300L), cumulative(300L, 100L, 0L), null, null, Status.PAID));
        assertThat(result.plannedProgressPct()).isNull(); assertThat(result.actualProgressPct()).isNull();
        assertThat(result.track().status()).isEqualTo("ON_TRACK");
    }
    @Test void zeroTargetHasNullAchievementRatioButCanBeComparedExactly() {
        var goals = planned(List.of(row(START, 100, 0, 0)));
        var result = evaluate(goals, cumulative(105L, 5L, 0L), null, null);
        assertThat(result.cumulativeExtra().achievementPct()).isNull(); assertThat(result.cumulativeExtra().gap()).isEqualTo(5);
        assertThat(result.track().status()).isEqualTo("AHEAD");
        assertThat(evaluate(goals, cumulative(100L, 0L, 0L), null, null).track().status()).isEqualTo("ON_TRACK");
    }
    @Test void noArrivedTargetAndVerifiedZeroIsOnTrackNotInsufficient() {
        var goals = planned(List.of(row(START.plusMonths(2), 100, 50, 0)));
        var result = evaluate(goals, cumulative(0L, 0L, 0L), monthly(0L, 0L, 0L), Status.UPCOMING);
        assertThat(result.plannedProgressPct()).isEqualByComparingTo("0.00");
        assertThat(result.cumulativeExtra().achievementPct()).isNull(); assertThat(result.track().status()).isEqualTo("ON_TRACK");
    }
    @Test void bulletComparesSavingAdditionsWhileInitialAssetsDoNotBecomeActualAdditionsOrPrincipalProgress() {
        var goals = planned(List.of(row(START, 0, 0, 100), row(START.plusMonths(1), 0, 0, 100), row(START.plusMonths(2), 0, 0, 100)));
        var result = PlanPerformanceCalculator.calculate(new Input(plan(1000L), RepaymentMethod.BULLET, AS_OF, goals,
            principal(0L), cumulative(0L, 0L, 150L), monthly(0L, 0L, 0L), new SavingBalance(7, 1, AS_OF, 1000L, 900L), Status.PAID));
        assertThat(result.cumulativeSaving().planned()).isEqualTo(200); assertThat(result.cumulativeSaving().actual()).isEqualTo(150);
        assertThat(result.cumulativeSaving().gap()).isEqualTo(-50); assertThat(result.track().status()).isEqualTo("BEHIND");
        assertThat(result.monthlySaving().planned()).isEqualTo(100); assertThat(result.savingProgressPct()).isEqualByComparingTo("90.00");
        assertThat(result.plannedProgressPct()).isEqualByComparingTo("0.00"); assertThat(result.actualProgressPct()).isEqualByComparingTo("0.00");
        assertThat(result.cumulativeExtra().gap()).isNull();
    }
    @Test void savingProgressHasNullZeroDenominatorAndDoesNotCapVerifiedOverfunding() {
        for (long principal : new long[]{0, 1000}) {
            var result = PlanPerformanceCalculator.calculate(new Input(plan(1000L), RepaymentMethod.BULLET, AS_OF,
                planned(List.of()), null, cumulative(0L, 0L, 0L), null, new SavingBalance(7, 1, AS_OF, principal, 1200L), null));
            if (principal == 0) assertThat(result.savingProgressPct()).isNull();
            else assertThat(result.savingProgressPct()).isEqualByComparingTo("120.00");
        }
    }
    @Test void percentageUsesHalfUpAndLargeValuesDoNotOverflowIntermediateMultiplication() {
        var result = PlanPerformanceCalculator.calculate(new Input(plan(3L), RepaymentMethod.EQUAL_PRINCIPAL, AS_OF,
            planned(List.of(row(START, 1, 0, 0))), principal(2L), cumulative(2L, 1L, 0L), null, null, null));
        assertThat(result.plannedProgressPct()).isEqualByComparingTo("33.33"); assertThat(result.actualProgressPct()).isEqualByComparingTo("66.67");
        var large = PlanPerformanceCalculator.calculate(new Input(plan(Long.MAX_VALUE), RepaymentMethod.EQUAL_PRINCIPAL, AS_OF,
            planned(List.of(row(START, Long.MAX_VALUE, 0, 0))), principal(Long.MAX_VALUE), cumulative(Long.MAX_VALUE, 0L, 0L), null, null, null));
        assertThat(large.actualProgressPct()).isEqualByComparingTo("100.00");
    }
    @Test void foreignOwnerLoanVersionOrCoverageCannotBeMixed() {
        for (var s : List.of(new Scope(8, 1, 2, START, AS_OF), new Scope(7, 2, 2, START, AS_OF),
                new Scope(7, 1, 3, START, AS_OF), scope(START, AS_OF.minusDays(1)))) {
            assertThatThrownBy(() -> evaluate(installment(), new Actuals(s, 0L, 0L, 0L), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> evaluate(installment(), null, cumulative(0L, 0L, 0L), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evaluate(new Planned(scope(START, AS_OF), List.of()), null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void duplicateForeignAndOutOfPeriodRowsAreRejectedInsteadOfDoubleCountingOrSkipping() {
        var r = row(START, 100, 50, 0);
        for (var rows : List.of(List.of(r, r), List.of(row(START.minusDays(1), 0, 0, 0)), List.of(row(AS_OF.plusMonths(1), 0, 0, 0)),
                List.of(new SimulationRow(START, 2, 0, 0, 0L, 0, 0, 0, 0, 0)))) {
            assertThatThrownBy(() -> evaluate(planned(rows), null, null, null)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void invalidAmountsAndContradictoryMonthTotalsAreRejected() {
        assertThatThrownBy(() -> cumulative(-1L, 0L, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cumulative(10L, 11L, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evaluate(installment(), cumulative(100L, 50L, 0L), monthly(101L, 50L, 0L), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evaluate(installment(), cumulative(1001L, 100L, 0L), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evaluate(planned(List.of(row(START, 1001, 0, 0))), null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void incorrectSavingBalanceScopeAndRepaymentMetricsAreRejected() {
        assertThatThrownBy(() -> PlanPerformanceCalculator.calculate(new Input(plan(1000L), RepaymentMethod.BULLET, AS_OF,
            null, null, null, null, new SavingBalance(8, 1, AS_OF, 1000L, 0L), null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evaluate(installment(), cumulative(0L, 0L, 10L), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlanPerformanceCalculator.calculate(new Input(plan(1000L), RepaymentMethod.BULLET, AS_OF,
            null, null, cumulative(10L, 10L, 0L), null, null, null))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void zeroStartBalanceHasNullRunnerRatiosAndAccumulationOverflowIsNotSilentlyWrapped() {
        var result = PlanPerformanceCalculator.calculate(new Input(plan(0L), RepaymentMethod.EQUAL_PRINCIPAL, AS_OF,
            planned(List.of()), principal(0L), cumulative(0L, 0L, 0L), null, null, null));
        assertThat(result.actualProgressPct()).isNull(); assertThat(result.plannedProgressPct()).isNull();
        assertThatThrownBy(() -> evaluate(planned(List.of(row(START, Long.MAX_VALUE, 0, 0), row(START.plusMonths(1), 1, 0, 0))), null, null, null))
            .isInstanceOf(ArithmeticException.class);
    }
    @Test void originalEngineRowsProvidePrincipalTargetsWithoutInterestOrFeeContamination() {
        var terms = new LoanTerms(1, 7, "가상 대출", LoanType.MORTGAGE, 2000, 1000, START, BigDecimal.ZERO,
            RateType.FIXED, RepaymentMethod.EQUAL_PRINCIPAL, null, null, START, 25, LocalDate.of(2027, 6, 25), null,
            null, 100L, PrepaymentPolicy.KEEP_PAYMENT, FeeMethod.FIXED, BigDecimal.TEN, null, null, 0, 0, LoanStatus.ACTIVE, null);
        Map<YearMonth, List<PlannedAmount>> amounts = new HashMap<>();
        for (int i = 0; i < 3; i++) amounts.put(YearMonth.from(START).plusMonths(i), List.of(new PlannedAmount(1, AllocationType.EXTRA_PAYMENT, 55, null)));
        var simulation = new DefaultRepaymentEngine().simulate(new SimulationInput(List.of(terms), START, 3, amounts, Map.of(), null, "KEEP_PLANS"));
        var result = evaluate(planned(simulation.rows()), cumulative(300L, 100L, 0L), monthly(0L, 0L, 0L), Status.PAID);
        assertThat(result.plannedProgressPct()).isEqualByComparingTo("30.00"); assertThat(result.cumulativeExtra().planned()).isEqualTo(100);
        assertThat(result.monthlyExtra().planned()).isEqualTo(50); assertThat(result.track().status()).isEqualTo("ON_TRACK");
        assertThat(result).isEqualTo(evaluate(planned(simulation.rows()), cumulative(300L, 100L, 0L), monthly(0L, 0L, 0L), Status.PAID));
    }
    @Test void planStartingThisMonthRequiresEqualKnownActualsForIdenticalPeriods() {
        var start = LocalDate.of(2026, 11, 10);
        var source = new SavedPlanSource(7, 1, 2, SavedPlanSource.Origin.PLAN_VERSION, true, "EXTRA_AMOUNT", null,
            start, null, LocalDate.of(2027, 6, 25), 55, 0, 1000L, null, null, null, Instant.parse("2026-11-10T00:00:00Z"));
        var scope = scope(start, AS_OF);
        assertThatThrownBy(() -> PlanPerformanceCalculator.calculate(new Input(source, RepaymentMethod.EQUAL_PRINCIPAL, AS_OF,
            null, null, new Actuals(scope, 100L, 50L, 0L), new Actuals(scope, 90L, 40L, 0L), null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void futurePrincipalTargetsCannotExceedThePlanStartingBalance() {
        assertThatThrownBy(() -> evaluate(planned(List.of(row(START, 500, 0, 0), row(START.plusMonths(2), 501, 0, 0))), null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void runnerReductionHasItsOwnCapturedBalanceBasisRatherThanAssumingTheEffectiveDate() {
        var result = PlanPerformanceCalculator.calculate(new Input(plan(1000L), RepaymentMethod.EQUAL_PRINCIPAL, AS_OF,
            installment(), principal(350L), cumulative(300L, 100L, 0L), null, null, Status.PAID));
        assertThat(result.actualProgressPct()).isEqualByComparingTo("35.00");
        assertThat(result.track().status()).isEqualTo("ON_TRACK");
        var unknown = PlanPerformanceCalculator.calculate(new Input(plan(1000L), RepaymentMethod.EQUAL_PRINCIPAL, AS_OF,
            installment(), null, cumulative(300L, 100L, 0L), null, null, Status.PAID));
        assertThat(unknown.actualProgressPct()).isNull();
    }
    @Test void principalReductionFromAnotherVersionOrDateCannotBeUsedForRunnerProgress() {
        for (var actual : List.of(new PrincipalActual(7, 1, 3, AS_OF, 300L), new PrincipalActual(8, 1, 2, AS_OF, 300L),
                new PrincipalActual(7, 2, 2, AS_OF, 300L), new PrincipalActual(7, 1, 2, AS_OF.minusDays(1), 300L))) {
            assertThatThrownBy(() -> PlanPerformanceCalculator.calculate(new Input(plan(1000L), RepaymentMethod.EQUAL_PRINCIPAL,
                AS_OF, installment(), actual, null, null, null, null))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
