package com.loanrun.repaymentplan;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.FinanceModels.RepaymentMethod;
import com.loanrun.repaymentplan.domain.SavedPlanSource;
import com.loanrun.repaymentplan.domain.SavedPlanSource.Origin;
import com.loanrun.repaymentplan.domain.PlanProjectionBasisPolicy;
import com.loanrun.repaymentplan.domain.PlanProjectionBasisPolicy.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PlanProjectionBasisPolicyTest {
    final LocalDate asOf=LocalDate.of(2026,11,19),registered=LocalDate.of(2026,1,1);
    Observation extra(int month,Long amount){return new Observation(1,2,YearMonth.of(2026,month),Metric.EXTRA_PRINCIPAL,amount!=null,amount);}
    Observation saving(int month,Long amount){return new Observation(1,2,YearMonth.of(2026,month),Metric.SAVING_ADDITION,amount!=null,amount);}
    SavedPlanSource plan(long cash,long saving,boolean configured){return new SavedPlanSource(1,2,1,Origin.REGISTRATION,configured,"MATURITY",null,
        LocalDate.of(2026,1,25),null,LocalDate.of(2027,1,25),cash,saving,1000000L,null,null,null,Instant.parse("2026-01-01T00:00:00Z"));}
    Basis select(RepaymentMethod method,SavedPlanSource plan,List<Observation> values){return PlanProjectionBasisPolicy.select(1,2,method,registered,asOf,plan,values);}
    @Test void confirmedZeroIsIncludedAndUnrecordedMonthsAreNotFilledWithZero() {
        var result=select(RepaymentMethod.EQUAL_PRINCIPAL,plan(999,0,true),List.of(extra(10,100L),extra(9,null),extra(8,0L)));
        assertThat(result.source()).isEqualTo(Source.ACTUAL_AVERAGE);assertThat(result.monthlyAmount()).isEqualTo(50);
        assertThat(result.amountKind()).isEqualTo(AmountKind.EXTRA_PRINCIPAL);assertThat(result.observedMonthCount()).isEqualTo(2);
        assertThat(result.usedMonths()).containsExactly(YearMonth.of(2026,8),YearMonth.of(2026,10));
    }
    @Test void actualZeroDoesNotFallBackToPositivePlan() {
        var result=select(RepaymentMethod.EQUAL_PAYMENT,plan(100000,0,true),List.of(extra(10,0L)));
        assertThat(result.monthlyAmount()).isZero();assertThat(result.source()).isEqualTo(Source.ACTUAL_AVERAGE);assertThat(result.observedMonthCount()).isEqualTo(1);
    }
    @Test void noObservationUsesKnownPlanCashWithoutCallingItPrincipal() {
        var result=select(RepaymentMethod.EQUAL_PAYMENT,plan(123,0,true),List.of(extra(10,null)));
        assertThat(result.source()).isEqualTo(Source.FALLBACK_PLAN);assertThat(result.monthlyAmount()).isEqualTo(123);
        assertThat(result.amountKind()).isEqualTo(AmountKind.EXTRA_CASH);assertThat(result.observedMonthCount()).isZero();
        assertThat(select(RepaymentMethod.EQUAL_PAYMENT,plan(0,0,true),List.of()).monthlyAmount()).isZero();
    }
    @Test void absentOrUnconfiguredPlanIsInsufficientNotInventedZero() {
        var result=select(RepaymentMethod.EQUAL_PAYMENT,plan(0,0,false),List.of());
        assertThat(result.source()).isEqualTo(Source.INSUFFICIENT);assertThat(result.monthlyAmount()).isNull();
        assertThat(select(RepaymentMethod.BULLET,null,List.of()).monthlyAmount()).isNull();
    }
    @Test void onlyPreviousSixCalendarMonthsAndRegisteredPeriodAreEligible() {
        var values=List.of(extra(4,999L),extra(5,999L),extra(6,0L),extra(10,100L),extra(11,999L),extra(12,999L));
        var result=PlanProjectionBasisPolicy.select(1,2,RepaymentMethod.EQUAL_PAYMENT,LocalDate.of(2026,6,15),asOf,plan(999,0,true),values);
        assertThat(result.monthlyAmount()).isEqualTo(50);assertThat(result.observedMonthCount()).isEqualTo(2);
    }
    @Test void bulletUsesOnlySavingAdditionsAndNeverExtraPrincipalOrInitialAssets() {
        var result=select(RepaymentMethod.BULLET,plan(999,123,true),List.of(extra(9,9999L),saving(9,100L),saving(10,0L)));
        assertThat(result.source()).isEqualTo(Source.ACTUAL_AVERAGE);assertThat(result.amountKind()).isEqualTo(AmountKind.SAVING_ADDITION);
        assertThat(result.monthlyAmount()).isEqualTo(50);assertThat(result.observedMonthCount()).isEqualTo(2);
        assertThat(select(RepaymentMethod.BULLET,plan(999,123,true),List.of(extra(9,9999L))).monthlyAmount()).isEqualTo(123);
    }
    @ParameterizedTest @EnumSource(value=RepaymentMethod.class,names={"EQUAL_PAYMENT","EQUAL_PRINCIPAL","GRACE_INSTALLMENT"})
    void installmentMethodsKeepPrincipalAndSavingMetricsSeparate(RepaymentMethod method) {
        var result=select(method,plan(999,0,true),List.of(saving(10,9999L),extra(10,10L)));
        assertThat(result.monthlyAmount()).isEqualTo(10);assertThat(result.amountKind()).isEqualTo(AmountKind.EXTRA_PRINCIPAL);
    }
    @Test void confirmedValuesDoNotRequireAFallbackPlan() {
        assertThat(select(RepaymentMethod.EQUAL_PAYMENT,null,List.of(extra(10,20L))).source()).isEqualTo(Source.ACTUAL_AVERAGE);
    }
    @Test void wonAverageUsesHalfUpAndDoesNotOverflowWhileSumming() {
        assertThat(select(RepaymentMethod.EQUAL_PAYMENT,null,List.of(extra(9,1L),extra(10,2L))).monthlyAmount()).isEqualTo(2);
        assertThat(select(RepaymentMethod.EQUAL_PAYMENT,null,List.of(extra(9,Long.MAX_VALUE),extra(10,Long.MAX_VALUE))).monthlyAmount()).isEqualTo(Long.MAX_VALUE);
    }
    @Test void duplicateSelectedMonthCannotBeDoubleCountedOrSilentlyChooseOneProof() {
        assertThatThrownBy(()->select(RepaymentMethod.EQUAL_PAYMENT,null,List.of(extra(10,0L),extra(10,null)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void ownerAndLoanScopesCannotBeMixedEvenIfAmountsAreEqual() {
        var foreign=new Observation(9,2,YearMonth.of(2026,10),Metric.EXTRA_PRINCIPAL,true,100L);
        assertThatThrownBy(()->select(RepaymentMethod.EQUAL_PAYMENT,null,List.of(foreign))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PlanProjectionBasisPolicy.select(9,2,RepaymentMethod.EQUAL_PAYMENT,registered,asOf,plan(1,0,true),List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unconfirmedRawZeroCannotBePassedAsActualAndNegativeAmountsAreRejected() {
        assertThatThrownBy(()->new Observation(1,2,YearMonth.of(2026,10),Metric.EXTRA_PRINCIPAL,false,0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Observation(1,2,YearMonth.of(2026,10),Metric.EXTRA_PRINCIPAL,true,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->extra(10,-1L)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void monthRolloverAdmitsTheNewlyCompletedMonthWithoutAddingMissingMonths() {
        var values=List.of(extra(11,100L));
        assertThat(select(RepaymentMethod.EQUAL_PAYMENT,null,values).source()).isEqualTo(Source.INSUFFICIENT);
        var next=PlanProjectionBasisPolicy.select(1,2,RepaymentMethod.EQUAL_PAYMENT,registered,LocalDate.of(2026,12,1),null,values);
        assertThat(next.source()).isEqualTo(Source.ACTUAL_AVERAGE);assertThat(next.observedMonthCount()).isEqualTo(1);
    }
}
