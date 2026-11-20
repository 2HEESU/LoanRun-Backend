package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskInputs;
import com.loanrun.risk.service.RiskRateStress;
import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class RiskRateStressTest {
    final RepaymentEngine engine=mock(RepaymentEngine.class);
    final YearMonth month=YearMonth.of(2026,11);
    RiskRateStress stress;
    @BeforeEach void configure(){
        var beans=new StaticListableBeanFactory();beans.addBean("engine",engine);stress=new RiskRateStress(beans.getBeanProvider(RepaymentEngine.class));
        when(engine.simulate(any())).thenAnswer(i->{SimulationInput input=i.getArgument(0);BigDecimal change=input.rateOverrides().getOrDefault(1L,BigDecimal.valueOf(6)).subtract(BigDecimal.valueOf(6));
            long delta=change.multiply(BigDecimal.valueOf(100)).longValueExact();
            return new SimulationResult(List.of(new SimulationRow(month.atDay(25),1,1000,1000+delta,null,0,0,0,10000,0),
                new SimulationRow(month.plusMonths(1).atDay(25),1,90000,90000,null,0,0,0,0,0)),null,null,null,null,null,false,"NONE",List.of());
        });
    }
    LoanTerms loan(long id,RateType rate){
        return new LoanTerms(id,7,"가상",LoanType.CREDIT,100000,100000,month.atDay(17),BigDecimal.valueOf(6),rate,RepaymentMethod.EQUAL_PAYMENT,null,null,
            month.atDay(25),25,month.plusYears(1).atDay(25),1000L,1000L,null,PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.NONE,BigDecimal.ZERO,null,null,0,0,LoanStatus.ACTIVE,null);
    }
    RiskInputs inputs(Long available,Long target,boolean variable){
        var capacity=new BudgetCapacity(month,available,available,999999,available,0,0,available,0,0L,"FORECAST","OPEN",List.of());
        return new RiskInputs(7,month.atDay(17),month,List.of(loan(1,variable?RateType.VARIABLE:RateType.FIXED),loan(2,RateType.FIXED)),List.of(),null,capacity,null,target,null,null);
    }
    @Test void delegatesWholeHouseholdButChangesOnlyVariableRatesAndCountsOnlyRequestedMonth(){
        var probe=stress.probe(inputs(100L,50L,true));assertThat(probe.available(BigDecimal.ONE)).isZero();
        assertThat(probe.basicBurdenDelta(new BigDecimal("1.00"))).isEqualTo(100);
        var capture=org.mockito.ArgumentCaptor.forClass(SimulationInput.class);verify(engine,times(2)).simulate(capture.capture());
        var changed=capture.getAllValues().get(1);assertThat(changed.rateOverrides()).containsExactlyEntriesOf(Map.of(1L,BigDecimal.valueOf(7)));
        assertThat(changed.terms()).hasSize(2);assertThat(changed.monthlyAmounts()).isEmpty();assertThat(changed.horizonMonths()).isEqualTo(1);
        assertThat(changed.asOf()).isEqualTo(month.atDay(17));assertThat(changed.allocationRule()).isEqualTo("NONE");
    }
    @Test void findsFirstStrictFailureOnHundredthPointGridUsingCashBeforeCarry(){
        var thresholds=stress.probe(inputs(100L,50L,true)).thresholds();
        assertThat(thresholds.reduceExtraFrom()).isEqualByComparingTo("0.51");assertThat(thresholds.basicAtRiskFrom()).isEqualByComparingTo("1.01");
        verify(engine,atMost(30)).simulate(any());
        assertThat(stress.probe(inputs(-1L,50L,true)).thresholds().basicAtRiskFrom()).isEqualByComparingTo("0");
    }
    @Test void fixedRatesHaveNoShockAndMissingActualCashOrTargetStaysUnknown(){
        var fixed=stress.probe(inputs(100L,50L,false));assertThat(fixed.available(BigDecimal.ONE)).isEqualTo(100);assertThat(fixed.thresholds().reduceExtraFrom()).isNull();
        var unknown=stress.probe(inputs(null,50L,true));assertThat(unknown.available(BigDecimal.ONE)).isNull();assertThat(unknown.thresholds().basicAtRiskFrom()).isNull();verifyNoInteractions(engine);
        assertThat(stress.probe(inputs(100L,null,true)).thresholds().reduceExtraFrom()).isNull();
    }
    @Test void unavailableEngineFailsInsteadOfPretendingRateChangeHasNoImpact(){
        var beans=new StaticListableBeanFactory();var absent=new RiskRateStress(beans.getBeanProvider(RepaymentEngine.class));
        assertThatThrownBy(()->absent.probe(inputs(100L,50L,true)).available(BigDecimal.ONE)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        assertThat(absent.probe(inputs(100L,50L,false)).available(BigDecimal.ONE)).isEqualTo(100);
    }
}
