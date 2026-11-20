package com.loanrun.repaymentsaving.service;

import com.fasterxml.jackson.databind.*;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.idempotency.IdempotencyService;
import com.loanrun.common.idempotency.IdempotencyService.StoredResponse;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.repaymentsaving.domain.SavingUpdatePolicy;
import com.loanrun.repaymentsaving.dto.SavingCreated;
import com.loanrun.repaymentsaving.repository.SavingWriteRepository;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;

@Service
public class SavingUpdateService {
    private final IdempotencyService idempotency;
    private final SavingWriteRepository writes;
    private final LoanSourceRepository sources;
    private final SavingListService summaries;
    private final ObjectProvider<BudgetRecalculationPort> budgets;
    private final RevisionPort revisions;
    private final ObjectMapper mapper;
    private final Clock clock;
    public SavingUpdateService(IdempotencyService idempotency,SavingWriteRepository writes,LoanSourceRepository sources,SavingListService summaries,
        ObjectProvider<BudgetRecalculationPort> budgets,RevisionPort revisions,ObjectMapper mapper,Clock clock) {
        this.idempotency=idempotency;this.writes=writes;this.sources=sources;this.summaries=summaries;this.budgets=budgets;
        this.revisions=revisions;this.mapper=mapper;this.clock=clock;
    }
    @Transactional
    public StoredResponse update(long userId,long savingId,JsonNode body,String key) {
        try {
            return idempotency.execute(userId,"PATCH","/api/repayment-savings/"+savingId+"/",key,body,"SAVING_CONFLICT",()->save(userId,savingId,body));
        } catch(ApiException ex) {
            if(ex.code().equals("SAVING_CONFLICT"))throw SavingUpdatePolicy.conflict();
            if(ex.code().equals("VALIDATION_ERROR")&&ex.errors().containsKey("Idempotency-Key"))throw invalid();
            throw ex;
        }
    }
    private StoredResponse save(long userId,long savingId,JsonNode body) {
        if(body==null||!body.isObject())throw invalid();
        var fields=body.fieldNames();while(fields.hasNext())if(!Set.of("saved_at","amount").contains(fields.next()))throw invalid();
        if(body.has("amount")&&(!body.path("amount").isIntegralNumber()||!body.path("amount").canConvertToLong()||body.path("amount").longValue()<1))throw invalid();
        LocalDate supplied=null;
        if(body.has("saved_at"))try {
            var node=body.path("saved_at");if(!node.isTextual()||!node.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw invalid();
            supplied=LocalDate.parse(node.textValue());if(supplied.getYear()<1)throw invalid();
        } catch(DateTimeParseException ex) {throw invalid();}
        var locked=writes.lockSaving(userId,savingId);var loan=locked.loan();var original=locked.saving();
        if(!loan.status().equals("ACTIVE"))throw SavingUpdatePolicy.conflict();
        if(!loan.method().equals("BULLET"))throw invalid();
        if(original.savedAt().isBefore(loan.snapshotDate()))throw SavingUpdatePolicy.conflict();
        var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var date=supplied==null?original.savedAt():supplied;long amount=body.has("amount")?body.path("amount").longValue():original.amount();
        if(date.isBefore(loan.snapshotDate())||date.isAfter(today))throw invalid();
        boolean changed=!date.equals(original.savedAt())||amount!=original.amount();
        if(changed) {
            var snapshot=sources.loanSnapshot(userId,loan.id(),today);if(snapshot.loans().size()!=1)throw ApiException.unavailable();
            try {SavingUpdatePolicy.verify(snapshot.loans().getFirst(),snapshot,original,date,amount);}
            catch(ArithmeticException ex) {throw SavingUpdatePolicy.conflict();}
            var budget=budgets.getIfAvailable();if(budget==null)throw ApiException.unavailable();
            writes.update(userId,original,date,amount);
            budget.recalculateFrom(userId,YearMonth.from(date.isBefore(original.savedAt())?date:original.savedAt()));
            revisions.bump(userId,"REPAYMENT_SAVING_UPDATED");
        }
        var query=new LinkedMultiValueMap<String,String>();query.add("loan_id",Long.toString(loan.id()));
        var summary=summaries.list(userId,query);var item=summary.results().stream().filter(s->s.savingId()==savingId).findFirst().orElseThrow(ApiException::unavailable);
        var response=new SavingCreated(item,loan.id(),summary.savingsBalance(),summary.requiredMonthlySaving(),summary.plannedSavingAmount(),
            summary.actualSavingAmount(),summary.savingProgressPct(),summary.remainingPrincipal(),changed);
        return new StoredResponse(200,mapper.valueToTree(ApiResponse.success("SAVING_UPDATE_SUCCESS","상환자금 적립 기록 수정 성공",response)));
    }
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"SAVING_VALIDATION_ERROR","만기일시상환 적립 입력값을 확인해 주세요.");}
}
