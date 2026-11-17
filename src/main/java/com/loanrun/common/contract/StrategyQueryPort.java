package com.loanrun.common.contract;

import com.loanrun.common.contract.FinanceModels.StrategyCandidate;

public interface StrategyQueryPort {
    StrategyCandidate requireCurrentCandidate(long userId, long reportId, String version, String code, long revision);
}
