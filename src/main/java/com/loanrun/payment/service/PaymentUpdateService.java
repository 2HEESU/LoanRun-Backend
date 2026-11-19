package com.loanrun.payment.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.idempotency.IdempotencyService;
import com.loanrun.common.idempotency.IdempotencyService.StoredResponse;
import com.loanrun.common.validation.JsonPatch;
import com.loanrun.loan.read.LoanSources.*;
import com.loanrun.payment.domain.*;
import com.loanrun.payment.repository.PaymentWriteRepository;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentUpdateService {
    private final IdempotencyService idempotency;private final PaymentLedgerCoordinator ledger;private final PaymentWriteRepository writes;private final ObjectMapper mapper;
    public PaymentUpdateService(IdempotencyService idempotency,PaymentLedgerCoordinator ledger,PaymentWriteRepository writes,ObjectMapper mapper){this.idempotency=idempotency;this.ledger=ledger;this.writes=writes;this.mapper=mapper;}
    @Transactional public StoredResponse update(long user,long id,JsonNode patch,String key) {
        try{return idempotency.execute(user,"PATCH","/api/payments/"+id+"/",key,patch,"PAYMENT_UPDATE_CONFLICT",()->save(user,id,patch));}
        catch(PaymentReplay.Conflict e){throw conflict();}catch(ApiException e){if(e.code().equals("PAYMENT_UPDATE_CONFLICT"))throw conflict();throw e;}
        catch(ArithmeticException e){throw PaymentRequest.invalid();}catch(org.springframework.dao.DataAccessException e){throw ApiException.unavailable();}
    }
    private StoredResponse save(long user,long id,JsonNode patch) {
        JsonPatch.fields(patch,"paid_at","amount","payment_type","due_item_id","principal_amount","extra_amount","interest_amount","fee_amount","savings_used_amount");
        long loan=writes.loanId(user,id);var source=ledger.lock(user,loan);var old=source.payments().stream().filter(p->p.id()==id).findFirst().orElseThrow(ApiException::unavailable);
        if(!PaymentReplay.mutable(old,PaymentReplay.latest(source)))throw conflict();var original=PaymentReplay.requests(source).stream().filter(p->p.id()==id).findFirst().orElseThrow(ApiException::unavailable);
        var full=mapper.createObjectNode();full.put("loan_id",loan);full.put("paid_at",old.paidAt().toString());full.put("amount",old.amount());full.put("payment_type",old.kind().name());full.set("due_item_id",mapper.valueToTree(original.dueId()));
        full.put("principal_amount",old.regularPrincipal());full.put("extra_amount",old.extraPrincipal());full.put("interest_amount",old.interest());full.put("fee_amount",old.fee());full.put("savings_used_amount",old.savingsUsed());
        int components=0;for(var k:List.of("principal_amount","extra_amount","interest_amount","fee_amount"))if(patch.has(k))components++;
        if(components!=0&&components!=4)throw PaymentRequest.invalid();
        boolean redivide=components==0&&(patch.has("amount")&&(!patch.path("amount").isIntegralNumber()||!patch.path("amount").canConvertToLong()||patch.path("amount").longValue()!=old.amount())||patch.has("payment_type")&&!patch.path("payment_type").equals(full.path("payment_type")));
        if(redivide)for(var k:List.of("principal_amount","extra_amount","interest_amount","fee_amount"))full.putNull(k);
        patch.fieldNames().forEachRemaining(k->full.set(k,patch.get(k)));var request=PaymentRequest.read(full,id,old.createdAt());
        if(request.date().isAfter(ledger.today()))throw PaymentRequest.invalid();if(request.date().isBefore(PaymentReplay.latest(source).effectiveDate()))throw conflict();
        boolean same=!request.automatic()&&request.date().equals(old.paidAt())&&request.amount()==old.amount()&&request.kind()==old.kind()&&Objects.equals(request.dueId(),original.dueId())&&request.principal()==old.regularPrincipal()&&request.extra()==old.extraPrincipal()&&request.interest()==old.interest()&&request.fee()==old.fee()&&request.used()==old.savingsUsed();
        if(same)return response(user,id,loan,request.dueId(),false);
        var all=new ArrayList<>(PaymentReplay.requests(source));all.removeIf(p->p.id()==id);all.add(request);var replay=PaymentReplay.replay(source,all);var entry=replay.entries().stream().filter(e->e.request().id()==id).findFirst().orElseThrow(ApiException::unavailable);
        if(request.date().equals(old.paidAt())&&request.kind()==old.kind()&&request.amount()==old.amount()&&request.used()==old.savingsUsed()&&Objects.equals(request.dueId(),original.dueId())&&entry.principal()==old.regularPrincipal()&&entry.extra()==old.extraPrincipal()&&entry.interest()==old.interest()&&entry.fee()==old.fee())return response(user,id,loan,request.dueId(),false);
        ledger.requireBudget();writes.update(user,loan,entry);ledger.apply(user,source,replay,request.date().isBefore(old.paidAt())?request.date():old.paidAt());return response(user,id,loan,request.dueId(),true);
    }
    private StoredResponse response(long user,long id,long loan,Long due,boolean changed) {var data=ledger.response(user,id,loan,due,false);data.put("recalculated",changed);data.put("report_stale",changed);return new StoredResponse(200,mapper.valueToTree(ApiResponse.success("PAYMENT_UPDATE_SUCCESS","납부 기록을 수정했습니다.",data)));}
    private static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"PAYMENT_UPDATE_CONFLICT","수정한 납부 기록이 다른 상환 이력과 충돌합니다.");}
}
