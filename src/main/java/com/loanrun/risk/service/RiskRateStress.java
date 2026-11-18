package com.loanrun.risk.service;

import com.loanrun.common.contract.RepaymentEngine;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskInputs;
import com.loanrun.risk.dto.RiskDtos.Thresholds;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Adapts Team 1 engine results; no interest or amortization formulas live here. */
@Service
public class RiskRateStress {
    private final ObjectProvider<RepaymentEngine> engine;
    public RiskRateStress(ObjectProvider<RepaymentEngine> engine) { this.engine=engine; }
    public Probe probe(RiskInputs inputs) { return new Probe(inputs); }

    /** One probe per calculation. Memoized engine outputs never outlive this input snapshot. */
    public final class Probe {
        private final RiskInputs inputs;
        private final boolean variable;
        private final Map<BigDecimal,Long> burdens=new HashMap<>();
        private Probe(RiskInputs inputs) {
            this.inputs=Objects.requireNonNull(inputs);
            this.variable=inputs.loans().stream().anyMatch(loan->loan.rateType()==RateType.VARIABLE);
        }
        public boolean hasVariableLoans() { return variable; }
        public Long available(BigDecimal increasePp) {
            validate(increasePp);
            Long baseline=inputs.capacity().availableCash();
            return baseline==null?null:Math.subtractExact(baseline,basicBurdenDelta(increasePp));
        }
        public long basicBurdenDelta(BigDecimal increasePp) {
            validate(increasePp);
            if(!variable||increasePp.signum()==0) return 0;
            long baseline=burden(BigDecimal.ZERO);
            return Math.subtractExact(burden(increasePp),baseline);
        }
        public Thresholds thresholds() {
            if(!variable||inputs.capacity().availableCash()==null) return new Thresholds(null,null);
            return new Thresholds(inputs.monthlyTarget()==null?null:
                RiskMath.firstFailure(i->available(BigDecimal.valueOf(i,2))<inputs.monthlyTarget(),500,new BigDecimal("0.01")),
                RiskMath.firstFailure(i->available(BigDecimal.valueOf(i,2))<0,500,new BigDecimal("0.01")));
        }
        private long burden(BigDecimal increasePp) {
            return burdens.computeIfAbsent(increasePp.stripTrailingZeros(),change->{
                var calculator=engine.getIfAvailable();if(calculator==null) throw ApiException.unavailable();
                Map<Long,BigDecimal> rates=new HashMap<>();
                for(var loan:inputs.loans()) if(loan.rateType()==RateType.VARIABLE) {
                    if(loan.annualRate()==null) throw ApiException.unavailable();
                    rates.put(loan.loanId(),loan.annualRate().add(change));
                }
                var request=new SimulationInput(inputs.loans(),inputs.asOf(),1,Map.of(),rates,null,"NONE");
                return calculator.simulate(request).rows().stream().filter(row->YearMonth.from(row.date()).equals(inputs.month()))
                    .map(row->Math.addExact(row.scheduledPrincipal(),row.interest())).reduce(0L,Math::addExact);
            });
        }
        private void validate(BigDecimal increasePp) {
            if(increasePp==null||increasePp.signum()<0||increasePp.compareTo(BigDecimal.valueOf(5))>0)
                throw new IllegalArgumentException("Rate probe increase must be between zero and five percentage points");
        }
    }
}
