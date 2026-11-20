package com.loanrun.loan.read;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.ReportHistoryQueryPort;
import com.loanrun.common.error.ApiException;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** The report scope is applied to source rows, never derived by splitting household totals. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class LoanReportHistoryQueryService implements ReportHistoryQueryPort {
    private final LoanHistoryQueryService source;
    public LoanReportHistoryQueryService(LoanHistoryQueryService source){this.source=source;}

    @Override public List<HistoryMonth> history(long userId,Long loanId,DateRange range) {
        try{return source.history(userId,loanId,range);}
        catch(ArithmeticException|DataAccessException e){throw ApiException.unavailable();}
    }
}
