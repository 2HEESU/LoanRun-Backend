package com.loanrun.loan.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.validation.JsonPatch;
import com.loanrun.finance.engine.*;
import com.loanrun.loan.dto.LoanCreated;
import com.loanrun.loan.read.*;
import com.loanrun.loan.repository.LoanContractWriteRepository;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanPatchService {
    private static final String[] FIELDS={"original_principal","remaining_principal","maturity_date","payment_day","contractual_monthly_payment","institution_name","product_name","start_date","next_rate_reset_date","prepayment_policy","prepayment_fee_method","prepayment_fee_rate","fee_start_date","fee_end_date","fee_exempt","rate_type","post_grace_method","rate_reset_months"};
    private final UserMutationLock locks;private final LoanContractWriteRepository writes;private final LoanSourceRepository sources;
    private final CurrentLoanTermsQueryService current;private final LoanInputValidator validator;private final RepaymentEngine engine;
    private final ObjectProvider<BudgetRecalculationPort> budgets;private final RevisionPort revisions;private final LoanListService list;private final ObjectMapper mapper;private final Clock clock;
    public LoanPatchService(UserMutationLock locks,LoanContractWriteRepository writes,LoanSourceRepository sources,CurrentLoanTermsQueryService current,LoanInputValidator validator,RepaymentEngine engine,ObjectProvider<BudgetRecalculationPort> budgets,RevisionPort revisions,LoanListService list,ObjectMapper mapper,Clock clock) {
        this.locks=locks;this.writes=writes;this.sources=sources;this.current=current;this.validator=validator;this.engine=engine;this.budgets=budgets;this.revisions=revisions;this.list=list;this.mapper=mapper;this.clock=clock;
    }
    @Transactional
    public LoanCreated update(long user,long id,JsonNode patch) {
        locks.lock(user);var l=writes.lock(user,id);JsonPatch.fields(patch,FIELDS);var input=input(l);var changed=new HashSet<String>();
        patch.fieldNames().forEachRemaining(k->{var old=input.get(k);var value=patch.get(k);boolean equal=old!=null&&(old.isNumber()&&value.isNumber()?old.decimalValue().compareTo(value.decimalValue())==0:old.equals(value));if(!equal)changed.add(k);input.set(k,value);});
        if(changed.isEmpty())return list.one(user,id);
        if(l.productId()!=null&&(patch.has("institution_name")||patch.has("product_name")))throw ApiException.validation("product_name","공시 상품의 표시명은 수정할 수 없습니다.");
        if(Set.of("institution_name","product_name").containsAll(changed)) {
            Map<String,Object> values=new LinkedHashMap<>();for(var k:changed)values.put(k,JsonPatch.text(input,k,false,Integer.MAX_VALUE));
            writes.updateContract(user,id,values,clock.instant());revisions.bump(user,"LOAN_UPDATED");return list.one(user,id);
        }
        var v=validator.validate(user,input);var t=v.terms();var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));var snapshot=sources.visibleLoanSnapshot(user,id,today);
        boolean correction=changed.contains("remaining_principal"),calendar=changed.stream().anyMatch(Set.of("maturity_date","payment_day","start_date","post_grace_method")::contains);
        if(snapshot.balances().stream().anyMatch(b->b.amount()>t.principalOriginal())||snapshot.payments().stream().anyMatch(p->p.paidAt().isBefore(v.startDate())||p.paidAt().isAfter(t.maturityDate()))||snapshot.savings().stream().anyMatch(s->s.savedAt().isBefore(v.startDate())||s.savedAt().isAfter(t.maturityDate())))throw conflict();
        var basis=current.current(user,id);if(basis.terms()==null)throw conflict();var old=basis.terms();
        var future=snapshot.dues().stream().filter(d->!d.dueDate().isBefore(today)&&!d.dueDate().isBefore(old.nextDueDate())).toList();
        if(future.size()>1||future.stream().anyMatch(d->d.confirmedUnpaid()||d.fee()!=0||snapshot.allocations().stream().anyMatch(a->a.dueItemId()==d.id())))throw conflict();
        if(calendar&&snapshot.dues().stream().anyMatch(d->!d.dueDate().isBefore(today)&&d.dueDate().isBefore(old.nextDueDate())))throw conflict();
        var next=calendar?t.nextDueDate():old.nextDueDate();if(next.isBefore(today)||next.isAfter(t.maturityDate()))throw conflict();
        Long contractual=changed.contains("contractual_monthly_payment")?t.contractualNextAmount():old.contractualNextAmount();
        Long anchor=old.baseAnchorAmount(),fixed=old.fixedPrincipal();
        var provisional=terms(t,l,correction?today:l.latestSnapshotDate(),next,null,null,old.currentSavingBalance(),contractual);
        if(correction||calendar) {
            var dates=RepaymentDates.remaining(provisional,today);int periods=(int)dates.stream().filter(d->t.graceEnd()==null||!d.isBefore(t.graceEnd())).count();
            var method=t.method()==RepaymentMethod.GRACE_INSTALLMENT?t.postGraceMethod():t.method();
            if(method!=RepaymentMethod.BULLET&&periods==0)throw conflict();
            anchor=method==RepaymentMethod.EQUAL_PAYMENT?RepaymentMath.annuity(t.outstanding(),t.annualRate(),periods):null;
            fixed=method==RepaymentMethod.EQUAL_PRINCIPAL?RepaymentMath.fixedPrincipal(t.outstanding(),periods):null;
        }
        if(t.outstanding()==0) {
            // A correction cannot erase already arrived interest/fees or an allocated future obligation.
            for(var d:snapshot.dues()) {
                long allocated=snapshot.allocations().stream().filter(a->a.dueItemId()==d.id()).mapToLong(a->Math.addExact(a.principal(),Math.addExact(a.interest(),a.fee()))).reduce(0,Math::addExact);
                if((d.dueDate().isBefore(today)||allocated>0||d.confirmedUnpaid())&&allocated<d.requiredAmount())throw conflict();
            }
        }
        var result=terms(t,l,correction?today:l.latestSnapshotDate(),next,anchor,fixed,old.currentSavingBalance(),contractual);
        var schedule=engine.simulate(new SimulationInput(List.of(result),today,1200,Map.of(),Map.of(),null,"NONE"));
        if(schedule.warnings().contains("CONTRACTUAL_AMOUNT_BELOW_INTEREST")||schedule.warnings().contains("BASIC_PAYMENT_BELOW_INTEREST"))throw conflict();
        var budget=budgets.getIfAvailable();if(budget==null)throw ApiException.unavailable();Map<String,Object> values=new LinkedHashMap<>();
        for(var k:changed)values.put(k,sqlValue(input.get(k),k));
        values.put("next_due_date",next);values.put("contractual_monthly_payment",contractual);values.put("base_anchor_amount",anchor);values.put("fixed_principal",fixed);values.put("grace_end_date",t.graceEnd());
        values.put("status",t.outstanding()==0?"PAID":"ACTIVE");
        if(correction){values.put("snapshot_date",today);values.put("snapshot_principal",t.outstanding());writes.correction(user,id,today,t.outstanding(),clock.instant());}
        writes.updateContract(user,id,values,clock.instant());long revision=revisions.bump(user,"LOAN_UPDATED");
        if(!future.isEmpty()) {
            var due=future.getFirst();if(t.outstanding()==0)writes.removeUnconfirmedDue(user,id,due.id());
            else {if(schedule.rows().isEmpty())throw conflict();writes.reviseDue(user,id,due.id(),schedule.rows().getFirst(),contractual,revision);}
        }
        budget.recalculateFrom(user,YearMonth.from(today));return list.one(user,id);
    }
    private Object sqlValue(JsonNode n,String k) {
        if(n.isNull())return null;if(k.endsWith("_date"))return LocalDate.parse(n.textValue());if(n.isBoolean())return n.booleanValue();
        if(n.isIntegralNumber())return n.longValue();if(n.isNumber())return n.decimalValue();return n.textValue();
    }
    private LoanTerms terms(LoanTerms t,LoanSources.Loan l,LocalDate snapshot,LocalDate next,Long anchor,Long fixed,long saving,Long contractual) {
        return new LoanTerms(l.loanId(),l.userId(),t.productName(),t.loanType(),t.principalOriginal(),t.outstanding(),snapshot,t.annualRate(),t.rateType(),t.method(),t.graceEnd(),t.postGraceMethod(),next,t.dueDay(),t.maturityDate(),contractual,anchor,fixed,t.prepaymentPolicy(),t.feeMethod(),t.feeRate(),t.feeStartDate(),t.feeEndDate(),t.initialSavingBalance(),saving,t.outstanding()==0?LoanStatus.PAID:LoanStatus.ACTIVE,t.rateResetDate());
    }
    private ObjectNode input(LoanSources.Loan l) {
        var n=mapper.createObjectNode();n.put("loan_form",l.loanForm());n.put("loan_type",l.loanType().name());n.putNull("product_id");n.put("institution_name",l.institutionName());n.put("product_name",l.productName());n.put("repayment_method",l.method().name());
        put(n,"original_principal",l.originalPrincipal());put(n,"remaining_principal",l.storedRemainingPrincipal());put(n,"annual_rate",l.annualRate());put(n,"rate_type",l.rateType());put(n,"rate_reset_months",l.rateResetMonths());put(n,"next_rate_reset_date",l.nextRateResetDate());put(n,"start_date",l.startDate());put(n,"maturity_date",l.maturityDate());put(n,"payment_day",l.paymentDay());put(n,"contractual_monthly_payment",l.contractualNextAmount());put(n,"grace_months",l.graceMonths());put(n,"post_grace_method",l.postGraceMethod());put(n,"prepayment_policy",l.prepaymentPolicy());put(n,"prepayment_fee_method",l.originalFeeMethod());put(n,"prepayment_fee_rate",l.feeRate());put(n,"fee_start_date",l.feeStart());put(n,"fee_end_date",l.feeEnd());put(n,"fee_exempt",l.feeExempt());put(n,"initial_savings_balance",l.initialSavings());return n;
    }
    private void put(ObjectNode n,String k,Object v){n.set(k,mapper.valueToTree(v));}
    private static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"LOAN_UPDATE_CONFLICT","상환 기록과 충돌하는 대출 정보입니다.");}
}
