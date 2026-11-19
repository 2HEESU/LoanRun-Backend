package com.loanrun.repaymentsaving.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.idempotency.IdempotencyService;
import com.loanrun.common.idempotency.IdempotencyService.StoredResponse;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.repaymentsaving.domain.SavingUpdatePolicy;
import com.loanrun.repaymentsaving.dto.SavingDeleted;
import com.loanrun.repaymentsaving.repository.SavingWriteRepository;
import java.time.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;

@Service
public class SavingDeleteService {
    private final IdempotencyService idempotency;
    private final SavingWriteRepository writes;
    private final LoanSourceRepository sources;
    private final SavingListService summaries;
    private final ObjectProvider<BudgetRecalculationPort> budgets;
    private final RevisionPort revisions;
    private final ObjectMapper mapper;
    private final Clock clock;
    public SavingDeleteService(IdempotencyService idempotency,SavingWriteRepository writes,LoanSourceRepository sources,SavingListService summaries,
        ObjectProvider<BudgetRecalculationPort> budgets,RevisionPort revisions,ObjectMapper mapper,Clock clock) {
        this.idempotency=idempotency;this.writes=writes;this.sources=sources;this.summaries=summaries;this.budgets=budgets;
        this.revisions=revisions;this.mapper=mapper;this.clock=clock;
    }
    @Transactional
    public StoredResponse delete(long userId,long savingId,String body,String key) {
        String request=body==null||body.isEmpty()?null:body;
        try {
            return idempotency.execute(userId,"DELETE","/api/repayment-savings/"+savingId+"/",key,mapper.valueToTree(request),"SAVING_CONFLICT",()->remove(userId,savingId,request));
        } catch(ApiException ex) {
            if(ex.code().equals("SAVING_CONFLICT"))throw SavingUpdatePolicy.conflict();
            if(ex.code().equals("VALIDATION_ERROR")&&ex.errors().containsKey("Idempotency-Key"))throw invalid();
            throw ex;
        }
    }
    private StoredResponse remove(long userId,long savingId,String body) {
        if(body!=null)throw invalid();
        var locked=writes.lockSaving(userId,savingId);var loan=locked.loan();var original=locked.saving();
        if(!loan.status().equals("ACTIVE")||original.savedAt().isBefore(loan.snapshotDate()))throw SavingUpdatePolicy.conflict();
        if(!loan.method().equals("BULLET"))throw invalid();
        var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));if(original.savedAt().isAfter(today))throw invalid();
        var snapshot=sources.loanSnapshot(userId,loan.id(),today);if(snapshot.loans().size()!=1)throw ApiException.unavailable();
        try {SavingUpdatePolicy.verifyRemoval(snapshot.loans().getFirst(),snapshot,original);}
        catch(ArithmeticException ex) {throw SavingUpdatePolicy.conflict();}
        var budget=budgets.getIfAvailable();if(budget==null)throw ApiException.unavailable();
        writes.delete(userId,original);
        budget.recalculateFrom(userId,YearMonth.from(original.savedAt()));
        revisions.bump(userId,"REPAYMENT_SAVING_DELETED");
        var query=new LinkedMultiValueMap<String,String>();query.add("loan_id",Long.toString(loan.id()));
        var summary=summaries.list(userId,query);
        var response=new SavingDeleted(savingId,true,loan.id(),summary.savingsBalance(),summary.requiredMonthlySaving(),summary.plannedSavingAmount(),
            summary.actualSavingAmount(),summary.savingProgressPct(),summary.remainingPrincipal(),true);
        return new StoredResponse(200,mapper.valueToTree(ApiResponse.success("SAVING_DELETE_SUCCESS","상환자금 적립 기록 삭제 성공",response)));
    }
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"SAVING_VALIDATION_ERROR","만기일시상환 적립 입력값을 확인해 주세요.");}
}
