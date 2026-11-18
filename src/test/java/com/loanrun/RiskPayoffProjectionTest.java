package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskInputs;
import com.loanrun.risk.service.RiskPayoffProjection;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class RiskPayoffProjectionTest {
    final RepaymentEngine engine=mock(RepaymentEngine.class);
    final YearMonth month=YearMonth.of(2026,11);
    RiskPayoffProjection projection;
    @BeforeEach void configure(){var beans=new StaticListableBeanFactory();beans.addBean("engine",engine);projection=new RiskPayoffProjection(beans.getBeanProvider(RepaymentEngine.class));}
    LoanTerms loan(long id){return new LoanTerms(id,7,"가상",LoanType.CREDIT,100000,100000,month.atDay(17),BigDecimal.valueOf(6),RateType.FIXED,
        RepaymentMethod.EQUAL_PAYMENT,null,null,month.atDay(25),25,month.plusYears(3).atDay(25),1000L,1000L,null,PrepaymentPolicy.KEEP_PAYMENT,
        FeeMethod.NONE,BigDecimal.ZERO,null,null,0,0,LoanStatus.ACTIVE,null);}
    PlanSnapshot plan(long id,LocalDate target){return new PlanSnapshot(id,true,target,null,100,0,0,"EXTRA_AMOUNT",null,month.atDay(1),3);}
    RiskInputs inputs(List<PlanSnapshot> plans){return new RiskInputs(7,month.atDay(17),month,List.of(loan(1),loan(2)),plans,null,null,null,200L,0L,null);}
    SimulationRow closed(long id,LocalDate date){return new SimulationRow(date,id,1000,0,0L,0,0,0,0,0);}
    SimulationResult result(List<SimulationRow> rows,boolean insufficient){return new SimulationResult(rows,month.plusYears(2).atDay(25),null,null,null,null,insufficient,"NONE",List.of("ENGINE_WARNING"));}
    @Test void comparesEachLoanTargetInsteadOfOnlyLatestHouseholdTarget(){
        var target1=month.plusMonths(6).atDay(25);var target2=month.plusYears(3).atDay(25);
        when(engine.simulate(any())).thenReturn(result(List.of(closed(1,target1.plusMonths(1)),closed(2,target2.minusYears(1))),false));
        var projected=projection.project(inputs(List.of(plan(1,target1),plan(2,target2))),Map.of(),Map.of());
        assertThat(projected.feasibility()).isEqualTo("MEDIUM");assertThat(projected.loanPayoffDates()).containsEntry(1L,target1.plusMonths(1));
        assertThat(projected.warnings()).containsExactly("ENGINE_WARNING");assertThatThrownBy(()->projected.loanPayoffDates().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void requiresKnownDatesForEveryLoanAndPreservesExplicitFundingFailure(){
        var target=month.plusYears(2).atDay(25);var inputs=inputs(List.of(plan(1,target),plan(2,target)));
        when(engine.simulate(any())).thenReturn(result(List.of(closed(1,target),closed(2,target)),false));
        assertThat(projection.project(inputs,Map.of(),Map.of()).feasibility()).isEqualTo("HIGH");
        assertThat(projection.project(inputs(List.of(plan(1,target),plan(2,null))),Map.of(),Map.of()).feasibility()).isNull();
        when(engine.simulate(any())).thenReturn(result(List.of(closed(1,target)),false));assertThat(projection.project(inputs,Map.of(),Map.of()).feasibility()).isNull();
        when(engine.simulate(any())).thenReturn(result(List.of(),true));assertThat(projection.project(inputs,Map.of(),Map.of()).feasibility()).isEqualTo("LOW");
    }
    @Test void forwardsExplicitRemainingTargetsAndRateOverridesWithoutRebuildingSchedules(){
        var amounts=Map.of(month,List.of(new PlannedAmount(1,AllocationType.EXTRA_PAYMENT,50,null)),month.plusMonths(1),List.of(new PlannedAmount(1,AllocationType.EXTRA_PAYMENT,100,null)));
        var rates=Map.of(1L,BigDecimal.valueOf(7));when(engine.simulate(any())).thenReturn(result(List.of(),false));
        projection.project(inputs(List.of()),amounts,rates);var capture=org.mockito.ArgumentCaptor.forClass(SimulationInput.class);verify(engine).simulate(capture.capture());
        var input=capture.getValue();assertThat(input.monthlyAmounts()).isEqualTo(amounts);assertThat(input.rateOverrides()).isEqualTo(rates);
        assertThat(input.asOf()).isEqualTo(month.atDay(17));assertThat(input.horizonMonths()).isEqualTo(1200);assertThat(input.allocationRule()).isEqualTo("KEEP_PLANS");
    }
    @Test void missingEngineCannotBecomeASuccessfulPayoffProjection(){
        var beans=new StaticListableBeanFactory();var absent=new RiskPayoffProjection(beans.getBeanProvider(RepaymentEngine.class));
        assertThatThrownBy(()->absent.project(inputs(List.of()),Map.of(),Map.of())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
}
