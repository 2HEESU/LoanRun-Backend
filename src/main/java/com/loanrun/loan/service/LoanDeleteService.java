package com.loanrun.loan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.idempotency.IdempotencyService;
import com.loanrun.common.idempotency.IdempotencyService.StoredResponse;
import com.loanrun.loan.dto.LoanDeleted;
import com.loanrun.loan.repository.LoanDeleteRepository;
import java.time.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanDeleteService {
    private final UserMutationLock locks;
    private final IdempotencyService idempotency;
    private final LoanDeleteRepository loans;
    private final ObjectProvider<BudgetRecalculationPort> budgets;
    private final RevisionPort revisions;
    private final ObjectMapper mapper;
    private final Clock clock;
    public LoanDeleteService(UserMutationLock locks,IdempotencyService idempotency,LoanDeleteRepository loans,
        ObjectProvider<BudgetRecalculationPort> budgets,RevisionPort revisions,ObjectMapper mapper,Clock clock) {
        this.locks=locks;this.idempotency=idempotency;this.loans=loans;this.budgets=budgets;this.revisions=revisions;this.mapper=mapper;this.clock=clock;
    }
    @Transactional
    public StoredResponse delete(long userId,long loanId,String body,String key) {
        String request=body==null||body.isEmpty()?null:body;
        if(key!=null)return idempotency.execute(userId,"DELETE","/api/loans/"+loanId+"/",key,mapper.valueToTree(request),
            "IDEMPOTENCY_CONFLICT",()->remove(userId,loanId,request));
        locks.lock(userId);return remove(userId,loanId,request);
    }
    private StoredResponse remove(long userId,long loanId,String body) {
        if(body!=null)throw ApiException.validation("non_field_errors","요청 본문을 입력할 수 없습니다.");
        var loan=loans.lock(userId,loanId);loans.rejectPayments(userId,loanId);
        var budget=budgets.getIfAvailable();if(budget==null)throw ApiException.unavailable();
        var now=clock.instant();
        loans.markDeleted(userId,loan,now);
        // Deletion hides current/future burden; preserved past actuals are not removed or restated.
        budget.recalculateFrom(userId,YearMonth.from(now.atZone(ZoneId.of("Asia/Seoul"))));
        revisions.bump(userId,"LOAN_DELETED");
        return new StoredResponse(200,mapper.valueToTree(ApiResponse.success("LOAN_DELETE_SUCCESS","대출을 삭제했습니다.",new LoanDeleted(loanId,true,"DELETED"))));
    }
}
