package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.service.RiskInputService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class RiskInputServiceTest {
    final LoanQueryPort loans=mock(LoanQueryPort.class);
    final PlanQueryPort plans=mock(PlanQueryPort.class);
    final BudgetQueryPort budgets=mock(BudgetQueryPort.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"),ZoneId.of("Asia/Seoul"));
    final YearMonth month=YearMonth.of(2026,11);
    RiskInputService service;
    @BeforeEach void configure(){
        var beans=new StaticListableBeanFactory();beans.addBean("loans",loans);beans.addBean("plans",plans);beans.addBean("budgets",budgets);
        service=new RiskInputService(beans.getBeanProvider(LoanQueryPort.class),beans.getBeanProvider(PlanQueryPort.class),beans.getBeanProvider(BudgetQueryPort.class),clock);
        when(loans.activeLoans(anyLong(),any())).thenReturn(List.of());when(plans.currentPlans(anyLong(),any())).thenReturn(List.of());
        when(budgets.financeSnapshot(anyLong(),any())).thenReturn(new FinanceSnapshot(month,IncomeType.REGULAR,3000000L,null,800000L,200000L,500000L,null,500000L,200000L,null,false,"DEFAULT"));
        when(budgets.capacity(anyLong(),any())).thenReturn(new BudgetCapacity(month,500000L,500000L,0,500000L,0,0,500000L,0,0L,"FORECAST","OPEN",List.of()));
        when(loans.dueSummary(anyLong(),any())).thenAnswer(i->new DueSummary(i.getArgument(1),800000,0,0,800000,0,null,1,0,0,0,0));
    }
    LoanTerms loan(long id,long user,long outstanding,RepaymentMethod method,RateType rate,LocalDate grace){
        return new LoanTerms(id,user,"가상",LoanType.CREDIT,outstanding,outstanding,LocalDate.of(2026,11,17),BigDecimal.valueOf(6),rate,method,grace,
            RepaymentMethod.EQUAL_PAYMENT,LocalDate.of(2026,11,25),25,LocalDate.of(2028,11,25),100000L,100000L,null,
            PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.NONE,BigDecimal.ZERO,null,null,0,0,LoanStatus.ACTIVE,null);
    }
    PlanSnapshot plan(long id,boolean configured,long extra,long saving,long required){
        return new PlanSnapshot(id,configured,LocalDate.of(2028,11,25),null,extra,saving,required,"EXTRA_AMOUNT",null,LocalDate.of(2026,11,1),3);
    }
    @Test void assemblesWholeHouseholdFactsAndUsesRequiredBulletSavingFloorWithoutCalculatingInterest(){
        when(loans.activeLoans(eq(7L),any())).thenReturn(List.of(loan(1,7,600000,RepaymentMethod.EQUAL_PAYMENT,RateType.FIXED,null),loan(2,7,400000,RepaymentMethod.BULLET,RateType.VARIABLE,null)));
        when(plans.currentPlans(eq(7L),any())).thenReturn(List.of(plan(1,true,200000,0,0),plan(2,true,0,100000,150000),plan(99,true,999999,0,0)));
        var result=service.snapshot(7,month);assertThat(result.monthlyTarget()).isEqualTo(350000);assertThat(result.requiredSaving()).isEqualTo(150000);
        assertThat(result.variableExposurePct()).isEqualByComparingTo("40");assertThat(result.plans()).hasSize(2);assertThat(result.finance().actualIncome()).isNull();
        assertThat(result.due().scheduledCash()).isEqualTo(800000);assertThat(result.capacity().availableCash()).isEqualTo(500000);
        verify(loans).activeLoans(7,LocalDate.of(2026,11,17));verify(plans).currentPlans(7,LocalDate.of(2026,11,17));
        verify(budgets).financeSnapshot(7,month);verify(budgets).capacity(7,month);verify(loans).dueSummary(7,month);
        assertThatThrownBy(()->result.loans().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void missingConfiguredPlanIsUnknownRatherThanZeroTarget(){
        when(loans.activeLoans(eq(7L),any())).thenReturn(List.of(loan(1,7,100000,RepaymentMethod.EQUAL_PAYMENT,RateType.FIXED,null)));
        when(plans.currentPlans(eq(7L),any())).thenReturn(List.of(plan(1,false,0,0,0)));
        var result=service.snapshot(7,month);assertThat(result.monthlyTarget()).isNull();assertThat(result.requiredSaving()).isNull();
    }
    @Test void noActiveLoansHasKnownZeroTargetsButNoExposureDenominator(){
        var result=service.snapshot(7,month);assertThat(result.monthlyTarget()).isZero();assertThat(result.requiredSaving()).isZero();assertThat(result.variableExposurePct()).isNull();
    }
    @Test void pastMonthUsesSameMonthEndForAllLoanAndPlanQueries(){
        var past=month.minusMonths(1);var result=service.snapshot(7,past);assertThat(result.asOf()).isEqualTo(LocalDate.of(2026,10,31));
        verify(loans).activeLoans(7,LocalDate.of(2026,10,31));verify(plans).currentPlans(7,LocalDate.of(2026,10,31));
        verify(budgets).financeSnapshot(7,past);verify(budgets).capacity(7,past);verify(loans).dueSummary(7,past);
    }
    @Test void graceTargetsStartOnGraceEndDateAndNeverBefore(){
        when(loans.activeLoans(eq(7L),any())).thenReturn(List.of(loan(1,7,100000,RepaymentMethod.GRACE_INSTALLMENT,RateType.FIXED,LocalDate.of(2026,11,18)),
            loan(2,7,100000,RepaymentMethod.GRACE_INSTALLMENT,RateType.FIXED,LocalDate.of(2026,11,17))));
        when(plans.currentPlans(eq(7L),any())).thenReturn(List.of(plan(1,true,200000,0,0),plan(2,true,100000,0,0)));
        assertThat(service.snapshot(7,month).monthlyTarget()).isEqualTo(100000);
        when(loans.activeLoans(eq(7L),any())).thenReturn(List.of(loan(1,7,100000,RepaymentMethod.GRACE_INSTALLMENT,RateType.FIXED,null)));
        assertThat(service.snapshot(7,month).monthlyTarget()).isNull();
    }
    @Test void absentPortsAndWrongOwnerFactsFailExplicitly(){
        var empty=new StaticListableBeanFactory();var unavailable=new RiskInputService(empty.getBeanProvider(LoanQueryPort.class),empty.getBeanProvider(PlanQueryPort.class),empty.getBeanProvider(BudgetQueryPort.class),clock);
        assertThatThrownBy(()->unavailable.snapshot(7,month)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        when(loans.activeLoans(eq(7L),any())).thenReturn(List.of(loan(1,8,100000,RepaymentMethod.EQUAL_PAYMENT,RateType.FIXED,null)));
        assertThatThrownBy(()->service.snapshot(7,month)).isInstanceOf(ApiException.class);verifyNoInteractions(budgets);
    }
}
