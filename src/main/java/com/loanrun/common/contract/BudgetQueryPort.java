package com.loanrun.common.contract;

import com.loanrun.common.contract.FinanceModels.*;
import java.time.YearMonth;
import java.util.List;

public interface BudgetQueryPort {
    BudgetCapacity capacity(long userId, YearMonth month);
    List<PlannedAmount> monthlyOverrides(long userId, YearMonth month);
    FinanceSnapshot financeSnapshot(long userId, YearMonth month);
}
