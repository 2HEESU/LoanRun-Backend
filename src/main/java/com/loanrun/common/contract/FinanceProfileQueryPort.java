package com.loanrun.common.contract;

import java.util.Optional;

public interface FinanceProfileQueryPort {
    Optional<IncomeType> incomeType(long userId);
}
