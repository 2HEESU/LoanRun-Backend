package com.loanrun.finance.engine;

import com.loanrun.common.contract.RepaymentEngine;
import com.loanrun.common.contract.FinanceModels.*;
import java.time.YearMonth;
import java.util.Map;

/** Team 1 internal principal-input capability; the shared cash-input contract remains unchanged. */
public interface PrincipalRepaymentEngine extends RepaymentEngine {
    SimulationResult simulateWithExtraPrincipal(SimulationInput input,Map<YearMonth,Map<Long,Long>> monthlyPrincipal);
}
