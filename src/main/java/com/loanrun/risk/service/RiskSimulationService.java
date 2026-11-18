package com.loanrun.risk.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskInputs;
import com.loanrun.risk.dto.RiskDtos.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RiskSimulationService {
    private final RiskInputService inputs;
    private final RiskRateStress rates;
    private final RiskPayoffProjection payoff;
    private final ObjectProvider<LoanQueryPort> loans;
    private final Clock clock;
    public RiskSimulationService(RiskInputService inputs,RiskRateStress rates,RiskPayoffProjection payoff,ObjectProvider<LoanQueryPort> loans,Clock clock){
        this.inputs=inputs;this.rates=rates;this.payoff=payoff;this.loans=loans;this.clock=clock;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Simulation simulate(long userId,ScenarioType type,List<Long> selected) {
        var facts=inputs.snapshot(userId,YearMonth.now(clock));
        for(Long id:selected) if(facts.loans().stream().noneMatch(loan->loan.loanId()==id))
            throw new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
        var probe=rates.probe(facts);
        long basic=Math.subtractExact(Math.addExact(facts.due().scheduledCash(),facts.due().previousOutstandingCash()),facts.due().savingsUsedForDue());
        long burden=type==ScenarioType.RATE_UP?Math.addExact(basic,probe.basicBurdenDelta(BigDecimal.ONE)):basic;
        Long originalIncome=facts.finance().planningIncome(),originalCash=facts.capacity().availableCash();
        long incomeLoss=type==ScenarioType.INCOME_DOWN&&originalIncome!=null?lostIncome(originalIncome,500):0;
        long extraExpense=type==ScenarioType.EXPENSE_UP?500000:0;
        Long income=originalIncome==null?null:Math.subtractExact(originalIncome,incomeLoss);
        Long available=type==ScenarioType.RATE_UP?probe.available(BigDecimal.ONE):originalCash;
        if(available!=null) available=Math.subtractExact(Math.subtractExact(available,incomeLoss),extraExpense);
        Long afterRate=probe.available(type==ScenarioType.RATE_UP?BigDecimal.valueOf(2):BigDecimal.ONE);
        if(afterRate!=null) afterRate=Math.subtractExact(Math.subtractExact(afterRate,incomeLoss),extraExpense);
        var baseline=assessment(facts,originalIncome,originalCash,basic,probe.available(BigDecimal.ONE));
        var scenario=assessment(facts,income,available,burden,afterRate);
        Long recurring=shortfall(type==ScenarioType.EXPENSE_UP?originalCash:available);
        var coverage=coverage(facts.finance().emergencyFund(),recurring);
        Long oneTime=available==null?null:type==ScenarioType.EXPENSE_UP?Math.subtractExact(shortfall(available),recurring):0L;
        var feasibility=feasibility(facts,type,available);
        List<String> warnings=new ArrayList<>(feasibility.warnings());
        if(type==ScenarioType.RATE_UP&&!probe.hasVariableLoans()) warnings.add("고정금리 대출에는 금리 상승폭이 적용되지 않습니다.");
        return new Simulation(new Baseline(originalIncome,basic,originalCash,baseline.level(),baseline.score()),
            new Scenario(income,burden,available,scenario.level(),feasibility.level(),scenario.score(),extraExpense,coverage.months(),coverage.status(),oneTime,
                type==ScenarioType.RATE_UP?1:0,type==ScenarioType.INCOME_DOWN?-5:0),
            new Delta(originalIncome==null?null:Math.subtractExact(income,originalIncome),Math.subtractExact(burden,basic),
                available==null||originalCash==null?null:Math.subtractExact(available,originalCash)),
            List.copyOf(new LinkedHashSet<>(warnings)),
            probe.thresholds(),incomeThresholds(facts),expenseThresholds(facts),type);
    }
    private RiskMath.Assessment assessment(RiskInputs s,Long income,Long cash,long burden,Long afterRate){
        return RiskMath.assess(income,cash,burden,s.finance().actualDiscretionaryExpenses(),s.finance().discretionaryLimit(),s.variableExposurePct(),
            s.loans().stream().anyMatch(loan->loan.rateType()==RateType.VARIABLE),afterRate,s.monthlyTarget(),!s.loans().isEmpty());
    }
    private record Feasibility(String level,List<String> warnings){}
    private Feasibility feasibility(RiskInputs s,ScenarioType type,Long available){
        if(available==null||s.monthlyTarget()==null||s.loans().isEmpty()) return new Feasibility(null,List.of());
        if(available<0||(s.requiredSaving()!=null&&available<s.requiredSaving())) return new Feasibility("LOW",List.of());
        if(available<s.monthlyTarget()) return new Feasibility("MEDIUM",List.of());
        var port=loans.getIfAvailable();if(port==null) throw ApiException.unavailable();
        var amounts=RiskScenarioAmounts.build(s,port.paymentSummary(s.userId(),s.month()),port.savingSummary(s.userId(),s.month()));
        Map<Long,BigDecimal> changed=new HashMap<>();
        if(type==ScenarioType.RATE_UP) for(var loan:s.loans()) if(loan.rateType()==RateType.VARIABLE) changed.put(loan.loanId(),loan.annualRate().add(BigDecimal.ONE));
        var result=payoff.project(s,amounts,changed);
        return new Feasibility(result.feasibility(),result.warnings());
    }
    private long lostIncome(long income,int steps){return BigDecimal.valueOf(income).multiply(BigDecimal.valueOf(steps)).divide(BigDecimal.valueOf(10000),0,RoundingMode.HALF_UP).longValueExact();}
    private Thresholds incomeThresholds(RiskInputs s){
        Long income=s.finance().planningIncome(),cash=s.capacity().availableCash();
        if(s.loans().isEmpty()||income==null||income<=0||cash==null) return new Thresholds(null,null);
        return new Thresholds(s.monthlyTarget()==null?null:RiskMath.firstFailure(i->Math.subtractExact(cash,lostIncome(income,i))<s.monthlyTarget(),10000,new BigDecimal("0.01")),
            RiskMath.firstFailure(i->Math.subtractExact(cash,lostIncome(income,i))<0,10000,new BigDecimal("0.01")));
    }
    private Thresholds expenseThresholds(RiskInputs s){return s.loans().isEmpty()?new Thresholds(null,null):new Thresholds(
        RiskMath.expenseThreshold(s.capacity().availableCash(),s.monthlyTarget()),RiskMath.expenseThreshold(s.capacity().availableCash(),0L));}
    private Long shortfall(Long cash){return cash==null?null:cash<0?Math.negateExact(cash):0;}
    private record Coverage(BigDecimal months,String status){}
    private Coverage coverage(Long fund,Long deficit){
        if(deficit==null) return new Coverage(null,"INSUFFICIENT");if(deficit==0) return new Coverage(null,"NOT_NEEDED");
        return fund==null?new Coverage(null,"INSUFFICIENT"):new Coverage(BigDecimal.valueOf(fund).divide(BigDecimal.valueOf(deficit),2,RoundingMode.HALF_UP),"AVAILABLE");
    }
}
