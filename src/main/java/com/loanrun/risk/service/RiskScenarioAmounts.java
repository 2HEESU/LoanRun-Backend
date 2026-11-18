package com.loanrun.risk.service;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskInputs;
import java.time.YearMonth;
import java.util.*;

/** Builds cash targets only. Team 1 decides scheduled payments, interest, fees and payoff. */
final class RiskScenarioAmounts {
    private RiskScenarioAmounts() {}
    static Map<YearMonth,List<PlannedAmount>> build(RiskInputs inputs,PaymentSummary paid,SavingSummary saved) {
        Map<YearMonth,List<PlannedAmount>> result=new HashMap<>();
        for(int i=0;i<1200;i++) {
            YearMonth month=inputs.month().plusMonths(i);List<PlannedAmount> amounts=new ArrayList<>();
            for(var loan:inputs.loans()) {
                var plan=inputs.plans().stream().filter(p->p.loanId()==loan.loanId()&&p.configured()).findFirst().orElseThrow(ApiException::unavailable);
                if(plan.effectiveDate()!=null&&month.atEndOfMonth().isBefore(plan.effectiveDate())) continue;
                if(loan.method()==RepaymentMethod.GRACE_INSTALLMENT
                        &&(loan.graceEnd()==null||month.atEndOfMonth().isBefore(loan.graceEnd()))) continue;
                boolean bullet=loan.method()==RepaymentMethod.BULLET;
                long goal=bullet?plan.monthlySaving():plan.monthlyExtraCash();
                if(i==0) goal=Math.max(Math.subtractExact(goal,bullet?saved.additionsByLoan().getOrDefault(loan.loanId(),0L):paid.extraCashByLoan().getOrDefault(loan.loanId(),0L)),0);
                amounts.add(new PlannedAmount(loan.loanId(),bullet?AllocationType.REPAYMENT_SAVING:AllocationType.EXTRA_PAYMENT,goal,null));
            }
            result.put(month,List.copyOf(amounts));
        }
        return Map.copyOf(result);
    }
}
