package com.loanrun.common.contract;

import java.time.YearMonth;

/** Team 2 implements. Join the caller's transaction; never call a Team 1 mutation. */
public interface BudgetRecalculationPort {
    void recalculateFrom(long userId, YearMonth earliestMonth);
}
