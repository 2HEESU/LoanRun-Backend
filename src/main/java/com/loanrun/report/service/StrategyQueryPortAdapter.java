package com.loanrun.report.service;

import com.loanrun.common.contract.FinanceModels.StrategyCandidate;
import com.loanrun.common.contract.StrategyQueryPort;
import org.springframework.stereotype.Service;

/** Exposes StrategyQueryPort separately so Team 1 @MockitoBean StrategyQueryPort tests preserve StrategyQueryService. */
@Service
public class StrategyQueryPortAdapter implements StrategyQueryPort {
    private final StrategyQueryService queries;

    public StrategyQueryPortAdapter(StrategyQueryService queries) {
        this.queries = queries;
    }

    @Override
    public StrategyCandidate requireCurrentCandidate(long userId, long reportId, String version, String code, long revision) {
        return queries.requireCurrentCandidate(userId, reportId, version, code, revision);
    }
}
