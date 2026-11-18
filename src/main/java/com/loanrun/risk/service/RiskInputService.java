package com.loanrun.risk.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskInputs;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal read only; HTTP entry points must perform their own PRO/selection checks. */
@Service
public class RiskInputService {
    private final ObjectProvider<LoanQueryPort> loans;
    private final ObjectProvider<PlanQueryPort> plans;
    private final ObjectProvider<BudgetQueryPort> budgets;
    private final Clock clock;
    public RiskInputService(ObjectProvider<LoanQueryPort> loans,ObjectProvider<PlanQueryPort> plans,
            ObjectProvider<BudgetQueryPort> budgets,Clock clock) {
        this.loans=loans;this.plans=plans;this.budgets=budgets;this.clock=clock;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public RiskInputs snapshot(long userId,YearMonth month) {
        Objects.requireNonNull(month);
        var loanPort=loans.getIfAvailable();var planPort=plans.getIfAvailable();var budgetPort=budgets.getIfAvailable();
        if(loanPort==null||planPort==null||budgetPort==null) throw ApiException.unavailable();
        LocalDate asOf=month.isBefore(YearMonth.now(clock))?month.atEndOfMonth():LocalDate.now(clock);
        var active=List.copyOf(loanPort.activeLoans(userId,asOf));
        if(active.stream().anyMatch(loan->loan.userId()!=userId)) throw ApiException.unavailable();
        Set<Long> activeIds=new HashSet<>();active.forEach(loan->activeIds.add(loan.loanId()));
        var saved=planPort.currentPlans(userId,asOf).stream().filter(plan->activeIds.contains(plan.loanId())).toList();
        Long target=0L,required=0L;
        for(var loan:active) {
            var matching=saved.stream().filter(plan->plan.loanId()==loan.loanId()&&plan.configured()).toList();
            if(matching.size()>1) throw ApiException.unavailable();
            if(matching.isEmpty()) {target=null;required=null;break;}
            var plan=matching.getFirst();
            if(loan.method()==RepaymentMethod.GRACE_INSTALLMENT&&loan.graceEnd()==null) {target=null;required=null;break;}
            if(loan.method()==RepaymentMethod.BULLET) {
                target=Math.addExact(target,Math.max(plan.monthlySaving(),plan.requiredMonthlySaving()));
                required=Math.addExact(required,plan.requiredMonthlySaving());
            } else if(loan.method()!=RepaymentMethod.GRACE_INSTALLMENT
                    ||(loan.graceEnd()!=null&&!asOf.isBefore(loan.graceEnd()))) {
                target=Math.addExact(target,plan.monthlyExtraCash());
            }
        }
        long total=active.stream().map(LoanTerms::outstanding).reduce(0L,Math::addExact);
        long variable=active.stream().filter(loan->loan.rateType()==RateType.VARIABLE).map(LoanTerms::outstanding).reduce(0L,Math::addExact);
        return new RiskInputs(userId,asOf,month,active,saved,budgetPort.financeSnapshot(userId,month),budgetPort.capacity(userId,month),
            loanPort.dueSummary(userId,month),target,required,RiskMath.ratio(variable,total));
    }
}
