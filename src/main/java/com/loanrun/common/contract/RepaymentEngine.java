package com.loanrun.common.contract;

import com.loanrun.common.contract.FinanceModels.*;

/** Team 1 owns the only pure engine implementation. No persistence, HTTP or system clock access. */
public interface RepaymentEngine {
    SimulationResult simulate(SimulationInput input);
    TargetResult minimumExtra(TargetInput input);
}
