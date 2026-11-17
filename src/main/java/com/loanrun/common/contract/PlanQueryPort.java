package com.loanrun.common.contract;

import com.loanrun.common.contract.FinanceModels.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public interface PlanQueryPort {
    List<PlanSnapshot> currentPlans(long userId, LocalDate asOf);
    List<PlannedAmount> plannedAllocations(long userId, YearMonth month);
    OverallState overallState(long userId);
}
