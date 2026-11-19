package com.loanrun.payment.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.UserMutationLock;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.idempotency.IdempotencyService;
import com.loanrun.common.idempotency.IdempotencyService.StoredResponse;
import com.loanrun.payment.domain.PaymentReplay;
import com.loanrun.payment.repository.PaymentWriteRepository;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class PaymentDeleteService {
    private final UserMutationLock locks;private final IdempotencyService idempotency;private final PaymentLedgerCoordinator ledger;private final PaymentWriteRepository writes;private final ObjectMapper mapper;
    public PaymentDeleteService(UserMutationLock locks,IdempotencyService idempotency,PaymentLedgerCoordinator ledger,PaymentWriteRepository writes,ObjectMapper mapper){this.locks=locks;this.idempotency=idempotency;this.ledger=ledger;this.writes=writes;this.mapper=mapper;}
    @Transactional public StoredResponse delete(long user,long id,String body,String key) {
        String input=body==null||body.isEmpty()?null:body;
        try{if(key!=null)return idempotency.execute(user,"DELETE","/api/payments/"+id+"/",key,mapper.valueToTree(input),"PAYMENT_DELETE_CONFLICT",()->remove(user,id,input));locks.lock(user);return remove(user,id,input);}
        catch(PaymentReplay.Conflict|ArithmeticException e){throw conflict();}catch(ApiException e){if(e.code().equals("PAYMENT_DELETE_CONFLICT"))throw conflict();throw e;}catch(org.springframework.dao.DataAccessException e){throw ApiException.unavailable();}
    }
    private StoredResponse remove(long user,long id,String body) {
        if(body!=null)throw ApiException.validation("non_field_errors","요청 본문을 입력할 수 없습니다.");long loan=writes.loanId(user,id);var source=ledger.lock(user,loan);var old=source.payments().stream().filter(p->p.id()==id).findFirst().orElseThrow(ApiException::unavailable);
        if(!PaymentReplay.mutable(old,PaymentReplay.latest(source)))throw conflict();var original=PaymentReplay.requests(source).stream().filter(p->p.id()==id).findFirst().orElseThrow(ApiException::unavailable);
        var all=new ArrayList<>(PaymentReplay.requests(source));all.removeIf(p->p.id()==id);var replay=PaymentReplay.replay(source,all);ledger.requireBudget();writes.delete(user,loan,id);ledger.apply(user,source,replay,old.paidAt());
        return new StoredResponse(200,mapper.valueToTree(ApiResponse.success("PAYMENT_DELETE_SUCCESS","납부 기록을 삭제했습니다.",ledger.deletionResponse(user,loan,id,original.dueId()))));
    }
    private static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"PAYMENT_DELETE_CONFLICT","삭제 시 다른 상환 기록과 충돌합니다.");}
}
