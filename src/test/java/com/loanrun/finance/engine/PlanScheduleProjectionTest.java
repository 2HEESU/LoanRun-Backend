package com.loanrun.finance.engine;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.payment.domain.DueStateCalculator.Status;
import com.loanrun.repaymentplan.domain.*;
import com.loanrun.repaymentplan.domain.PlanPerformanceCalculator.*;
import com.loanrun.repaymentplan.domain.PlanScheduleProjection.Reason;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PlanScheduleProjectionTest {
    static final Instant CAPTURED = Instant.parse("2026-01-01T00:00:00Z");
    static final YearMonth MARCH = YearMonth.of(2026, 3);
    final PlanScheduleProjection calculator = new PlanScheduleProjection(new DefaultRepaymentEngine());

    LoanFixture loan() {
        var f = new LoanFixture(RepaymentMethod.EQUAL_PRINCIPAL);
        f.original = 2400; f.balance = 1200; f.fixedPrincipal = 100L; f.rate = BigDecimal.ZERO;
        f.feeMethod = FeeMethod.FIXED; f.feeRate = BigDecimal.TEN;
        return f;
    }
    SavedPlanSource source(LoanFixture f, long cash, long saving) {
        return source(f.terms(), f.balance, f.next, CAPTURED, true, cash, saving);
    }
    SavedPlanSource source(LoanTerms terms, Long start, LocalDate effective, Instant captured, boolean configured, long cash, long saving) {
        return new SavedPlanSource(7, 1, 2, SavedPlanSource.Origin.PLAN_VERSION, configured, "MATURITY", null,
            effective, null, LocalDate.of(2026, 12, 25), cash, saving, start, terms, null, null, captured);
    }

    @Test void originalCashGoalProducesFeeAdjustedPrincipalAndFeedsThePerformanceCalculator() {
        var plan = source(loan(), 55, 0); var result = calculator.throughMonth(plan, MARCH);
        assertThat(result.reason()).isEqualTo(Reason.AVAILABLE); assertThat(result.planned().rows()).hasSize(3);
        assertThat(result.planned().rows()).allSatisfy(row -> {
            assertThat(row.scheduledPrincipal()).isEqualTo(100); assertThat(row.extraPrincipal()).isEqualTo(50);
            assertThat(row.extraCash()).isEqualTo(55); assertThat(row.fee()).isEqualTo(5);
        });
        var asOf = LocalDate.of(2026, 3, 19);
        var cumulative = new Actuals(new Scope(7, 1, 2, plan.effectiveDate(), asOf), 300L, 100L, 0L);
        var monthly = new Actuals(new Scope(7, 1, 2, MARCH.atDay(1), asOf), 0L, 0L, 0L);
        var performance = PlanPerformanceCalculator.calculate(new Input(plan, RepaymentMethod.EQUAL_PRINCIPAL, asOf,
            result.planned(), new PrincipalActual(7, 1, 2, asOf, 300L), cumulative, monthly, null, Status.UPCOMING));
        assertThat(performance.plannedProgressPct()).isEqualByComparingTo("25.00");
        assertThat(performance.cumulativeExtra().planned()).isEqualTo(100); assertThat(performance.monthlyExtra().planned()).isEqualTo(50);
        assertThat(performance.track().status()).isEqualTo("ON_TRACK");
    }
    @Test void missingOriginalTermsOrStartBalanceNeverGetsReconstructedFromTheCurrentLoan() {
        var f = loan();
        assertThat(calculator.throughMonth(source(null, 1200L, f.next, CAPTURED, true, 55, 0), MARCH).reason()).isEqualTo(Reason.MISSING_BASIS);
        var missing = calculator.throughMonth(source(f.terms(), null, f.next, CAPTURED, true, 55, 0), MARCH);
        assertThat(missing.reason()).isEqualTo(Reason.MISSING_BASIS); assertThat(missing.planned()).isNull(); assertThat(missing.simulation()).isNull();
    }
    @Test void unknownFeeWithPositiveExtraCannotCertifyThePrincipalSchedule() {
        var f = loan(); f.feeMethod = FeeMethod.UNKNOWN;
        var result = calculator.throughMonth(source(f, 55, 0), MARCH);
        assertThat(result.reason()).isEqualTo(Reason.FEE_UNCERTAIN); assertThat(result.planned()).isNull();
        assertThat(result.simulation().feeComparisonBasis()).isEqualTo("GROSS_INTEREST");
        assertThat(result.simulation().totalCash()).isNull();
    }
    @Test void unknownFeeDoesNotInvalidateKnownBasicPrincipalWhenThereIsNoExtraPayment() {
        var f = loan(); f.feeMethod = FeeMethod.UNKNOWN;
        var result = calculator.throughMonth(source(f, 0, 0), MARCH);
        assertThat(result.reason()).isEqualTo(Reason.AVAILABLE);
        assertThat(result.planned().rows()).allSatisfy(row -> assertThat(row.extraPrincipal()).isZero());
        assertThat(result.simulation().totalFee()).isNull();
    }
    @Test void expiredUnknownFeesCanProduceAnExactPrincipalSchedule() {
        var f = loan(); f.feeMethod = FeeMethod.UNKNOWN; f.feeEnd = LocalDate.of(2026, 1, 24);
        var result = calculator.throughMonth(source(f, 55, 0), MARCH);
        assertThat(result.reason()).isEqualTo(Reason.AVAILABLE);
        assertThat(result.planned().rows()).allSatisfy(row -> assertThat(row.extraPrincipal()).isEqualTo(55));
    }
    @Test void bulletUsesCapturedAssetsAndSavingGoalWithoutReducingPrincipalOrInterest() {
        var f = loan(); f.method = RepaymentMethod.BULLET; f.rate = new BigDecimal("12"); f.saving = f.initialSaving = 400;
        f.feeMethod = FeeMethod.UNKNOWN;
        var result = calculator.throughMonth(source(f, 0, 100), MARCH);
        assertThat(result.reason()).isEqualTo(Reason.AVAILABLE);
        assertThat(result.planned().rows()).allSatisfy(row -> {
            assertThat(row.scheduledPrincipal()).isZero(); assertThat(row.extraPrincipal()).isZero();
            assertThat(row.interest()).isEqualTo(12); assertThat(row.saving()).isEqualTo(100);
        });
        assertThat(result.planned().rows().getLast().remainingSaving()).isEqualTo(700);
    }
    @Test void bulletFundingShortfallIsNotMistakenForMissingContractualScheduleOrSuccessfulPayoff() {
        var f = loan(); f.method = RepaymentMethod.BULLET;
        var result = calculator.throughMonth(source(f, 0, 0), YearMonth.of(2026, 12));
        assertThat(result.reason()).isEqualTo(Reason.AVAILABLE); assertThat(result.simulation().insufficient()).isTrue();
        assertThat(result.simulation().projectedPayoffDate()).isNull();
        assertThat(result.planned().rows().getLast().scheduledPrincipal()).isEqualTo(1200);
        assertThat(result.simulation().warnings()).contains("BULLET_SAVINGS_SHORTFALL");
    }
    @Test void annuityAndGraceRetainTheOriginalEngineBehavior() {
        var f = loan(); f.method = RepaymentMethod.EQUAL_PAYMENT; f.anchor = 100L;
        assertThat(calculator.throughMonth(source(f, 55, 0), MARCH).planned().rows().getFirst().scheduledPrincipal()).isEqualTo(100);
        f.method = RepaymentMethod.GRACE_INSTALLMENT; f.postGrace = RepaymentMethod.EQUAL_PRINCIPAL;
        f.grace = LocalDate.of(2026, 3, 25);
        var result = calculator.throughMonth(source(f, 55, 0), MARCH);
        assertThat(result.planned().rows().subList(0, 2)).allSatisfy(row -> {
            assertThat(row.extraPrincipal()).isZero(); assertThat(row.scheduledPrincipal()).isZero();
        });
        assertThat(result.simulation().warnings()).contains("GRACE_EXTRA_NOT_APPLIED");
        assertThat(result.planned().rows().getLast().extraPrincipal()).isEqualTo(50);
    }
    @Test void keepMaturityRepricesTheOriginalScheduleAfterExtraPayment() {
        var f = loan(); f.policy = PrepaymentPolicy.KEEP_MATURITY;
        var result = calculator.throughMonth(source(f, 55, 0), MARCH);
        assertThat(result.planned().rows().get(1).scheduledPrincipal()).isEqualTo(95);
    }
    @Test void captureDateUsesKstAndDoesNotUseAnOlderBalanceSnapshotDateAsTheSimulationStart() {
        var f = loan(); f.snapshot = LocalDate.of(2025, 12, 1);
        var result = calculator.throughMonth(source(f.terms(), 1200L, f.next, Instant.parse("2025-12-31T15:00:00Z"), true, 55, 0), MARCH);
        assertThat(result.basisDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(result.planned().rows()).hasSize(3); assertThat(result.planned().scope().from()).isEqualTo(f.next);
    }
    @Test void captureAfterEffectivityOrSnapshotAfterCaptureIsNotAValidHistoricalBasis() {
        var f = loan();
        assertThat(calculator.throughMonth(source(f.terms(), 1200L, f.next, Instant.parse("2026-01-26T00:00:00Z"), true, 55, 0), MARCH).reason())
            .isEqualTo(Reason.BASIS_TIME_UNCERTAIN);
        f.snapshot = LocalDate.of(2026, 1, 2);
        assertThat(calculator.throughMonth(source(f, 55, 0), MARCH).reason()).isEqualTo(Reason.BASIS_TIME_UNCERTAIN);
    }
    @Test void earlierDueCannotBeSilentlySkippedWithoutEvidenceOfItsSettlement() {
        var f = loan();
        var result = calculator.throughMonth(source(f.terms(), 1200L, LocalDate.of(2026, 2, 25), CAPTURED, true, 55, 0), MARCH);
        assertThat(result.reason()).isEqualTo(Reason.PRIOR_DUE_UNRESOLVED); assertThat(result.planned()).isNull();
    }
    @Test void unsetOrNotYetEffectivePlanDoesNotGenerateAZeroSchedule() {
        var f = loan();
        assertThat(calculator.throughMonth(source(f.terms(), 1200L, f.next, CAPTURED, false, 0, 0), MARCH).reason()).isEqualTo(Reason.NOT_CONFIGURED);
        var result = calculator.throughMonth(source(f, 55, 0), YearMonth.of(2025, 12));
        assertThat(result.reason()).isEqualTo(Reason.NOT_EFFECTIVE); assertThat(result.planned()).isNull();
    }
    @Test void unsupportedHorizonDoesNotProduceAPartialScheduleLabeledComplete() {
        var f = loan(); f.maturity = LocalDate.of(2126, 1, 25);
        assertThat(calculator.throughMonth(source(f, 55, 0), MARCH).reason()).isEqualTo(Reason.HORIZON_UNSUPPORTED);
    }
    @Test void monthEndCoverageAndPostPayoffZeroPeriodsAreExplicit() {
        var result = calculator.throughMonth(source(loan(), 55, 0), YearMonth.of(2030, 1));
        assertThat(result.planned().scope().through()).isEqualTo(LocalDate.of(2030, 1, 31));
        assertThat(result.planned().rows()).hasSize(8); assertThat(result.planned().rows().getLast().date()).isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(result.planned().rows().getLast().balance()).isZero();
        assertThat(result.planned().rows().stream().mapToLong(row -> row.scheduledPrincipal() + row.extraPrincipal()).sum()).isEqualTo(1200);
    }
    @Test void shortProjectionCanCoverItsRequestedWindowEvenWhenFullTermIsNotFinished() {
        var result = calculator.throughMonth(source(loan(), 55, 0), MARCH);
        assertThat(result.reason()).isEqualTo(Reason.AVAILABLE);
        assertThat(result.simulation().warnings()).contains("HORIZON_EXHAUSTED");
        assertThat(result.simulation().projectedPayoffDate()).isNull();
    }
    @Test void conflictingOwnerLoanOrStartPrincipalAndWrongMetricAreRejected() {
        var f = loan();
        f.owner = 8;
        assertThatThrownBy(() -> calculator.throughMonth(source(f, 55, 0), MARCH)).isInstanceOf(IllegalArgumentException.class);
        f.owner = 7; f.id = 2;
        assertThatThrownBy(() -> calculator.throughMonth(source(f, 55, 0), MARCH)).isInstanceOf(IllegalArgumentException.class);
        f.id = 1;
        assertThatThrownBy(() -> calculator.throughMonth(source(f.terms(), 1199L, f.next, CAPTURED, true, 55, 0), MARCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.throughMonth(source(f, 55, 10), MARCH)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void changingUnrelatedCurrentTermsCannotAlterTheSavedBasisAndResultsAreRepeatable() {
        var f = loan(); var original = source(f, 55, 0);
        var first = calculator.throughMonth(original, MARCH);
        f.balance = 100; f.rate = new BigDecimal("99");
        assertThat(calculator.throughMonth(original, MARCH)).isEqualTo(first);
        assertThat(original.basisTerms().outstanding()).isEqualTo(1200); assertThat(first.simulation().totalInterest()).isZero();
        assertThatThrownBy(() -> first.planned().rows().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void originalMonthEndDueDatesRemainStableAcrossShortMonths() {
        var f = loan(); f.day = 31; f.next = LocalDate.of(2026, 1, 31);
        var result = calculator.throughMonth(source(f, 55, 0), MARCH);
        assertThat(result.planned().rows()).extracting(SimulationRow::date)
            .containsExactly(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31));
    }
}
