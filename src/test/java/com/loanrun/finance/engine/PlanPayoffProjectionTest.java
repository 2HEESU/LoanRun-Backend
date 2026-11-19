package com.loanrun.finance.engine;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.repaymentplan.domain.*;
import com.loanrun.repaymentplan.domain.PlanProjectionBasisPolicy.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PlanPayoffProjectionTest {
    static final LocalDate AS_OF = LocalDate.of(2026, 1, 1), REGISTERED = LocalDate.of(2025, 1, 1);
    final PlanPayoffProjection calculator = new PlanPayoffProjection(new DefaultRepaymentEngine());

    LoanFixture loan() {
        var f = new LoanFixture(RepaymentMethod.EQUAL_PRINCIPAL);
        f.original = f.balance = 1200; f.fixedPrincipal = 100L; f.rate = new BigDecimal("12");
        f.feeMethod = FeeMethod.FIXED; f.feeRate = new BigDecimal("10");
        return f;
    }
    Observation observation(Metric metric, Long amount) {
        return new Observation(7, 1, YearMonth.of(2025, 12), metric, amount != null, amount);
    }
    SavedPlanSource plan(long cash, long saving, boolean configured) {
        return new SavedPlanSource(7, 1, 1, SavedPlanSource.Origin.REGISTRATION, configured, "MATURITY", null,
            REGISTERED, null, LocalDate.of(2026, 12, 25), cash, saving, 1200L, null, null, null,
            Instant.parse("2025-01-01T00:00:00Z"));
    }
    PlanPayoffProjection.Projection project(LoanFixture f, SavedPlanSource plan, Observation... observations) {
        return calculator.project(f.terms(), REGISTERED, AS_OF, 12, plan, List.of(observations));
    }

    @Test void observedPrincipalKeepsItsAmountAndAddsFeesInsteadOfReducingPrincipalToFitCash() {
        var result = project(loan(), plan(999, 0, true), observation(Metric.EXTRA_PRINCIPAL, 200L));
        assertThat(result.basis().source()).isEqualTo(Source.ACTUAL_AVERAGE);
        var s = result.simulation();
        assertThat(s.projectedPayoffDate()).isEqualTo(LocalDate.of(2026, 4, 25));
        assertThat(s.totalPrincipal()).isEqualTo(1200); assertThat(s.totalInterest()).isEqualTo(30);
        assertThat(s.totalFee()).isEqualTo(80); assertThat(s.totalCash()).isEqualTo(1310);
        assertThat(s.rows()).hasSize(4).allSatisfy(row -> {
            assertThat(row.extraPrincipal()).isEqualTo(200); assertThat(row.extraCash()).isEqualTo(220);
        });
    }
    @Test void fallbackCashRetainsTheExistingFeeInclusiveBudgetAndHasDifferentPayoff() {
        var result = project(loan(), plan(200, 0, true), observation(Metric.EXTRA_PRINCIPAL, null));
        assertThat(result.basis().amountKind()).isEqualTo(AmountKind.EXTRA_CASH);
        assertThat(result.simulation().projectedPayoffDate()).isEqualTo(LocalDate.of(2026, 5, 25));
        var first = result.simulation().rows().getFirst();
        assertThat(first.extraPrincipal()).isEqualTo(182); assertThat(first.fee()).isEqualTo(18);
        assertThat(first.extraCash()).isEqualTo(200);
    }
    @Test void confirmedZeroOverridesTheSavedExtraGoalWithoutInventingExtraRepayment() {
        var result = project(loan(), plan(200, 0, true), observation(Metric.EXTRA_PRINCIPAL, 0L));
        assertThat(result.basis().observedMonthCount()).isEqualTo(1);
        assertThat(result.simulation().projectedPayoffDate()).isEqualTo(LocalDate.of(2026, 12, 25));
        assertThat(result.simulation().rows()).allSatisfy(row -> assertThat(row.extraPrincipal()).isZero());
        assertThat(result.simulation().totalInterest()).isEqualTo(78);
    }
    @Test void feesExpirePerFutureRowWithoutChangingThePrincipalProjection() {
        var f = loan(); f.feeStart = AS_OF; f.feeEnd = LocalDate.of(2026, 2, 24);
        var s = project(f, null, observation(Metric.EXTRA_PRINCIPAL, 200L)).simulation();
        assertThat(s.totalFee()).isEqualTo(20);
        assertThat(s.rows()).extracting(SimulationRow::fee).containsExactly(20L, 0L, 0L, 0L);
        assertThat(s.projectedPayoffDate()).isEqualTo(LocalDate.of(2026, 4, 25));
    }
    @Test void linearFeeUsesEachProjectionDateAndRoundsToWon() {
        var f = loan(); f.feeMethod = FeeMethod.LINEAR; f.feeStart = AS_OF; f.feeEnd = LocalDate.of(2026, 5, 1);
        var s = project(f, null, observation(Metric.EXTRA_PRINCIPAL, 200L)).simulation();
        assertThat(s.rows()).extracting(SimulationRow::fee).containsExactly(16L, 11L, 6L, 1L);
        assertThat(s.totalFee()).isEqualTo(34); assertThat(s.totalCash()).isEqualTo(1264);
    }
    @Test void unknownFeePreservesGrossProjectionButNeverClaimsKnownFeeOrTotalCash() {
        var f = loan(); f.feeMethod = FeeMethod.UNKNOWN;
        var s = project(f, null, observation(Metric.EXTRA_PRINCIPAL, 200L)).simulation();
        assertThat(s.projectedPayoffDate()).isEqualTo(LocalDate.of(2026, 4, 25));
        assertThat(s.totalFee()).isNull(); assertThat(s.totalCash()).isNull();
        assertThat(s.feeComparisonBasis()).isEqualTo("GROSS_INTEREST");
        assertThat(s.warnings()).contains("FEE_UNKNOWN");
    }
    @Test void finalExtraIsCappedAtRemainingPrincipalAndFeeUsesOnlyThatPrincipal() {
        var s = project(loan(), null, observation(Metric.EXTRA_PRINCIPAL, Long.MAX_VALUE)).simulation();
        assertThat(s.rows()).hasSize(1);
        var row = s.rows().getFirst();
        assertThat(row.scheduledPrincipal()).isEqualTo(100); assertThat(row.extraPrincipal()).isEqualTo(1100);
        assertThat(row.fee()).isEqualTo(110); assertThat(row.extraCash()).isEqualTo(1210); assertThat(row.balance()).isZero();
    }
    @ParameterizedTest @EnumSource(value = PrepaymentPolicy.class)
    void extraPrincipalUsesTheExistingPrepaymentPolicy(PrepaymentPolicy policy) {
        var f = loan(); f.policy = policy;
        var s = project(f, null, observation(Metric.EXTRA_PRINCIPAL, 200L)).simulation();
        assertThat(s.rows().get(1).scheduledPrincipal()).isEqualTo(policy == PrepaymentPolicy.KEEP_PAYMENT ? 100 : 82);
        assertThat(s.totalPrincipal()).isEqualTo(1200);
        assertThat(s.rows()).allSatisfy(row -> assertThat(row.balance()).isNotNegative());
    }
    @Test void annuityAndGraceReuseTheSameEngineWithoutApplyingExtraDuringGrace() {
        var f = loan(); f.method = RepaymentMethod.EQUAL_PAYMENT; f.rate = BigDecimal.ZERO; f.anchor = 100L;
        assertThat(project(f, null, observation(Metric.EXTRA_PRINCIPAL, 200L)).simulation().projectedPayoffDate())
            .isEqualTo(LocalDate.of(2026, 4, 25));
        f.method = RepaymentMethod.GRACE_INSTALLMENT; f.grace = LocalDate.of(2026, 3, 25);
        f.postGrace = RepaymentMethod.EQUAL_PRINCIPAL;
        var s = project(f, null, observation(Metric.EXTRA_PRINCIPAL, 200L)).simulation();
        assertThat(s.rows().subList(0, 2)).allSatisfy(row -> {
            assertThat(row.scheduledPrincipal()).isZero(); assertThat(row.extraPrincipal()).isZero();
        });
        assertThat(s.warnings()).contains("GRACE_EXTRA_NOT_APPLIED");
        assertThat(s.totalPrincipal()).isEqualTo(1200);
    }
    @Test void bulletSavingDoesNotReducePrincipalOrInterestAndDoesNotInventEarlyPayment() {
        var f = loan(); f.method = RepaymentMethod.BULLET; f.feeMethod = FeeMethod.NONE;
        f.initialSaving = f.saving = 400;
        var s = project(f, plan(0, 0, true), observation(Metric.SAVING_ADDITION, 200L)).simulation();
        assertThat(s.projectedPayoffDate()).isEqualTo(f.maturity);
        assertThat(s.totalInterest()).isEqualTo(144); assertThat(s.totalCash()).isEqualTo(944);
        assertThat(s.rows().stream().mapToLong(SimulationRow::saving).sum()).isEqualTo(800);
        assertThat(s.rows().subList(0, 11)).allSatisfy(row -> {
            assertThat(row.balance()).isEqualTo(1200); assertThat(row.scheduledPrincipal()).isZero();
            assertThat(row.extraPrincipal()).isZero(); assertThat(row.interest()).isEqualTo(12);
        });
    }
    @Test void bulletConfirmedZeroCannotFallBackAndUnderfundedMaturityHasNullPayoff() {
        var f = loan(); f.method = RepaymentMethod.BULLET; f.saving = 400;
        var s = project(f, plan(0, 100, true), observation(Metric.SAVING_ADDITION, 0L)).simulation();
        assertThat(s.projectedPayoffDate()).isNull(); assertThat(s.insufficient()).isTrue();
        assertThat(s.warnings()).contains("BULLET_SAVINGS_SHORTFALL");
        assertThat(s.rows()).allSatisfy(row -> assertThat(row.saving()).isZero());
    }
    @Test void bulletWithoutObservedMonthsUsesSavedSavingGoal() {
        var f = loan(); f.method = RepaymentMethod.BULLET;
        var result = project(f, plan(0, 100, true), observation(Metric.SAVING_ADDITION, null));
        assertThat(result.basis().source()).isEqualTo(Source.FALLBACK_PLAN);
        assertThat(result.simulation().projectedPayoffDate()).isEqualTo(f.maturity);
        assertThat(result.simulation().insufficient()).isFalse();
    }
    @Test void missingInputIsNotASyntheticZeroScenarioButConfiguredZeroIsSimulated() {
        assertThat(project(loan(), null, observation(Metric.EXTRA_PRINCIPAL, null)).simulation()).isNull();
        assertThat(project(loan(), plan(0, 0, false)).simulation()).isNull();
        assertThat(project(loan(), plan(0, 0, true)).simulation().projectedPayoffDate()).isEqualTo(loan().maturity);
    }
    @Test void incompleteHorizonDoesNotInventAPayoffDate() {
        var result = calculator.project(loan().terms(), REGISTERED, AS_OF, 3, null,
            List.of(observation(Metric.EXTRA_PRINCIPAL, 200L)));
        assertThat(result.simulation().rows()).hasSize(3); assertThat(result.simulation().projectedPayoffDate()).isNull();
        assertThat(result.simulation().warnings()).contains("HORIZON_EXHAUSTED");
    }
    @Test void passedCurrentDueIsNotSimulatedAgainAndRepeatedCallsPreserveSources() {
        var terms = loan().terms(); var observations = List.of(observation(Metric.EXTRA_PRINCIPAL, 200L));
        var date = LocalDate.of(2026, 1, 26);
        var first = calculator.project(terms, REGISTERED, date, 12, null, observations);
        var second = calculator.project(terms, REGISTERED, date, 12, null, observations);
        assertThat(first).isEqualTo(second); assertThat(terms.outstanding()).isEqualTo(1200);
        assertThat(first.simulation().rows().getFirst().date()).isEqualTo(LocalDate.of(2026, 2, 25));
        assertThat(first.simulation().projectedPayoffDate()).isEqualTo(LocalDate.of(2026, 5, 25));
    }
    @Test void invalidHorizonAndInactiveLoansAreRejectedEvenWhenInputIsUnknown() {
        assertThatThrownBy(() -> calculator.project(loan().terms(), REGISTERED, AS_OF, 0, null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        var f = loan(); f.status = LoanStatus.PAID;
        assertThatThrownBy(() -> project(f, null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void futurePlanCannotBeAppliedBeforeItsEffectiveDate() {
        var future = new SavedPlanSource(7, 1, 2, SavedPlanSource.Origin.PLAN_VERSION, true, "MATURITY", null,
            LocalDate.of(2026, 2, 25), null, LocalDate.of(2026, 12, 25), 200, 0, 1200L, null, null, null,
            Instant.parse("2026-01-01T00:00:00Z"));
        assertThatThrownBy(() -> project(loan(), future)).isInstanceOf(IllegalArgumentException.class);
    }
}
