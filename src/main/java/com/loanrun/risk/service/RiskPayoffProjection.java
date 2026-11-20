package com.loanrun.risk.service;

import com.loanrun.common.contract.RepaymentEngine;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskInputs;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Caller supplies the scenario's remaining monthly targets; all payoff calculations belong to Team 1. */
@Service
public class RiskPayoffProjection {
    public record Projection(String feasibility,LocalDate projectedPayoffDate,Map<Long,LocalDate> loanPayoffDates,List<String> warnings) {
        public Projection {loanPayoffDates=Map.copyOf(loanPayoffDates);warnings=List.copyOf(warnings);}
    }
    private final ObjectProvider<RepaymentEngine> engine;
    public RiskPayoffProjection(ObjectProvider<RepaymentEngine> engine) {this.engine=engine;}
    public Projection project(RiskInputs inputs,Map<YearMonth,List<PlannedAmount>> amounts,Map<Long,BigDecimal> rates) {
        if(inputs.loans().isEmpty()) return new Projection(null,null,Map.of(),List.of());
        var calculator=engine.getIfAvailable();if(calculator==null) throw ApiException.unavailable();
        var request=new SimulationInput(inputs.loans(),inputs.asOf(),1200,amounts,rates,null,"KEEP_PLANS");
        var result=calculator.simulate(request);Map<Long,LocalDate> dates=new HashMap<>();
        Set<Long> active=new HashSet<>();inputs.loans().forEach(loan->active.add(loan.loanId()));
        for(var row:result.rows()) if(active.contains(row.loanId())&&row.balance()==0&&(row.scheduledPrincipal()>0||row.extraPrincipal()>0))
            dates.merge(row.loanId(),row.date(),(a,b)->a.isBefore(b)?a:b);
        boolean unknown=result.projectedPayoffDate()==null,late=false;
        for(var loan:inputs.loans()) {
            var plans=inputs.plans().stream().filter(plan->plan.loanId()==loan.loanId()&&plan.configured()).toList();
            if(plans.size()!=1||plans.getFirst().targetPayoffDate()==null||dates.get(loan.loanId())==null) {unknown=true;continue;}
            if(dates.get(loan.loanId()).isAfter(plans.getFirst().targetPayoffDate())) late=true;
        }
        String feasibility=result.insufficient()?"LOW":late?"MEDIUM":unknown?null:"HIGH";
        return new Projection(feasibility,result.projectedPayoffDate(),dates,result.warnings());
    }
}
