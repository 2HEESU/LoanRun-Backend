package com.loanrun.risk.service;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.risk.domain.RiskInputs;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RiskScenarioAmountsTest {
    final YearMonth month=YearMonth.of(2026,11);
    LoanTerms loan(long id,RepaymentMethod method,LocalDate grace){return new LoanTerms(id,7,"가상",LoanType.CREDIT,100000,100000,month.atDay(17),BigDecimal.valueOf(6),
        RateType.FIXED,method,grace,null,month.atDay(25),25,month.plusYears(1).atDay(25),1000L,1000L,null,PrepaymentPolicy.KEEP_PAYMENT,
        FeeMethod.NONE,BigDecimal.ZERO,null,null,0,0,LoanStatus.ACTIVE,null);}
    PlanSnapshot plan(long id,long extra,long saving,long required,LocalDate effective){return new PlanSnapshot(id,true,month.plusYears(1).atDay(25),null,extra,saving,required,"EXTRA_AMOUNT",null,effective,3);}
    RiskInputs inputs(List<LoanTerms> loans,List<PlanSnapshot> plans){return new RiskInputs(7,month.atDay(17),month,loans,plans,null,null,null,null,null,null);}
    PaymentSummary paid(Map<Long,Long> amounts){return new PaymentSummary(month,0,0,0,0,0,0,0,true,0,0,amounts);}
    SavingSummary saved(Map<Long,Long> amounts){return new SavingSummary(month,0,0,0,0,true,0,amounts);}
    @Test void subtractsActualCurrentUsageButNeverRaisesUserSavingGoalToRequiredAmount(){
        var inputs=inputs(List.of(loan(1,RepaymentMethod.EQUAL_PAYMENT,null),loan(2,RepaymentMethod.BULLET,null)),
            List.of(plan(1,100,0,0,month.atDay(1)),plan(2,0,100,150,month.atDay(1))));
        var result=RiskScenarioAmounts.build(inputs,paid(Map.of(1L,40L)),saved(Map.of(2L,150L)));
        assertThat(result.get(month)).extracting(PlannedAmount::cashAmount).containsExactly(60L,0L);
        assertThat(result.get(month.plusMonths(1))).extracting(PlannedAmount::cashAmount).containsExactly(100L,100L);
        assertThat(result.get(month).get(1).allocationType()).isEqualTo(AllocationType.REPAYMENT_SAVING);
        assertThat(result).hasSize(1200);
    }
    @Test void doesNotApplyGoalsInWholeMonthsBeforeGraceEndOrPlanEffectiveDate(){
        var inputs=inputs(List.of(loan(1,RepaymentMethod.GRACE_INSTALLMENT,month.plusMonths(1).atDay(25)),loan(2,RepaymentMethod.EQUAL_PAYMENT,null)),
            List.of(plan(1,500,0,0,month.atDay(1)),plan(2,100,0,0,month.plusMonths(2).atDay(25))));
        var result=RiskScenarioAmounts.build(inputs,paid(Map.of()),saved(Map.of()));assertThat(result.get(month)).isEmpty();
        assertThat(result.get(month.plusMonths(1))).extracting(PlannedAmount::loanId).containsExactly(1L);
        assertThat(result.get(month.plusMonths(2))).extracting(PlannedAmount::loanId).containsExactly(1L,2L);
    }
}
