package com.loanrun.risk.service;

import com.loanrun.risk.dto.RiskDtos.*;
import com.loanrun.risk.domain.RiskScenarioInput;
import com.loanrun.risk.service.RiskDetailHistorySource.Recorded;
import java.math.BigDecimal;
import java.time.*;
import org.springframework.stereotype.Service;

@Service
public class RiskDetailService {
    private final RiskDetailInputService inputs;private final RiskSimulationService simulations;private final RiskRateStress rates;
    public RiskDetailService(RiskDetailInputService inputs,RiskSimulationService simulations,RiskRateStress rates){this.inputs=inputs;this.simulations=simulations;this.rates=rates;}
    public Detail read(long userId,Long selected,YearMonth month){
        var captured=inputs.capture(userId,selected,month);if(captured.frozen()!=null)return captured.frozen();
        return calculate(captured.current(),captured.history(),selected);
    }
    /** Evaluates immutable sources; caller captures ownership/confirmed history before invoking. */
    public Detail calculate(RiskScenarioInput captured,Recorded recorded,Long selected){
        var facts=captured.household();var finance=facts.finance();var month=facts.month();
        var simulation=simulations.evaluate(captured,ScenarioType.CURRENT).response();
        long basic=Math.subtractExact(Math.addExact(facts.due().scheduledCash(),facts.due().previousOutstandingCash()),facts.due().savingsUsedForDue());
        var probe=rates.probe(facts);
        Long discretionary=finance.actualDiscretionaryExpenses()!=null?finance.actualDiscretionaryExpenses():finance.discretionaryExpenses();
        var assessment=RiskMath.assess(finance.planningIncome(),facts.capacity().availableCash(),basic,
            discretionary,finance.discretionaryLimit(),facts.variableExposurePct(),probe.hasVariableLoans(),
            probe.available(BigDecimal.ONE),facts.monthlyTarget(),!facts.loans().isEmpty());
        var indicators=new Indicators(RiskMath.display(RiskMath.ratio(facts.capacity().availableCash(),finance.planningIncome())),
            RiskMath.display(RiskMath.ratio(basic,finance.planningIncome())),facts.capacity().availableCash(),RiskMath.display(facts.variableExposurePct()),
            discretionary,finance.discretionaryLimit(),RiskMath.display(RiskMath.ratio(discretionary,finance.discretionaryLimit())));
        var upcoming=RiskDetailPresentation.events(facts.loans(),selected,facts.asOf());
        var history=RiskDetailPresentation.history(recorded,selected,month);
        var current=simulation.scenario();
        Level safeLevel=assessment.level()!=null?assessment.level():Level.STABLE;
        var safeChecks=assessment.checks().stream()
            .map(c->c.level()!=null?c:new Check(c.code(),c.label(),c.ratioPct(),Level.STABLE,c.weight(),c.points()))
            .toList();
        return new Detail(safeLevel,indicators,upcoming,history.events(),assessment.dataStatus(),safeChecks,simulation.rateThresholds(),
            history.changes(),true,history.summary(),simulation.incomeThresholds(),simulation.expenseThresholds(),assessment.score(),current.emergencyCoverageMonths(),
            current.emergencyCoverageStatus(),current.oneTimeShortfall());
    }
}
