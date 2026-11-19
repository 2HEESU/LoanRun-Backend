package com.loanrun.payment.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.*;
import com.loanrun.payment.read.StoredDueProjection;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PaymentScheduleService {
    private final StoredDueQueryService dues;private final LoanSourceRepository loans;private final ObjectProvider<PlanPresentationPort> plans;private final ObjectProvider<BudgetQueryPort> budgets;private final ObjectMapper mapper;private final Clock clock;
    public PaymentScheduleService(StoredDueQueryService dues,LoanSourceRepository loans,ObjectProvider<PlanPresentationPort> plans,ObjectProvider<BudgetQueryPort> budgets,ObjectMapper mapper,Clock clock){this.dues=dues;this.loans=loans;this.plans=plans;this.budgets=budgets;this.mapper=mapper;this.clock=clock;}
    public JsonNode read(long user,String monthText,String loanText) {
        YearMonth month;try{if(monthText==null||!monthText.matches("[0-9]{4}-[0-9]{2}")||monthText.startsWith("0000"))throw new IllegalArgumentException();month=YearMonth.parse(monthText);}catch(DateTimeException|IllegalArgumentException e){throw invalidMonth();}
        Long id=null;if(loanText!=null)try{id=Long.valueOf(loanText);if(id<=0||!loanText.matches("[0-9]+"))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw ApiException.validation("loan_id","대출 ID를 확인해 주세요.");}
        try {
            var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));if(id!=null)loans.loan(user,id);
            var snapshot=id==null?loans.snapshot(user,today):loans.loanSnapshot(user,id,today);
            var rows=dues.range(user,new DateRange(month.atDay(1),month.atEndOfMonth()),id).stream().filter(r->r.source().loanStatus()!=LoanStatus.DELETED||r.source().due().dueDate().isBefore(today)).toList();
            for(var l:snapshot.loans())if(l.storedStatus()==LoanStatus.ACTIVE&&!month.isBefore(YearMonth.from(l.initialPlan().effectiveDate()))&&!month.isAfter(YearMonth.from(l.maturityDate()))&&rows.stream().noneMatch(r->r.source().due().loanId()==l.loanId()))throw ApiException.unavailable();
            var views=views(user,month,today);ObjectNode result=mapper.createObjectNode();result.put("month",month.toString());var items=result.putArray("due_items");var events=result.putArray("calendar_events");
            long required=0,remaining=0,completed=0,actual=0,extraActual=0,savingActual=0;Long plannedExtra=0L,plannedSaving=0L;var seen=new HashSet<Long>();
            for(var row:rows) {
                var item=item(row,snapshot,views.get(row.source().due().loanId()));items.add(item);var event=events.addObject();event.put("date",row.source().due().dueDate().toString());event.put("type","REGULAR_DUE");event.put("loan_id",row.source().due().loanId());event.put("due_item_id",row.source().due().id());event.put("amount",row.source().due().requiredAmount());
                required=Math.addExact(required,row.source().due().requiredAmount());remaining=Math.addExact(remaining,row.state().outstandingAmount());if(row.state().status()==com.loanrun.payment.domain.DueStateCalculator.Status.PAID)completed++;
                if(seen.add(row.source().due().loanId())){plannedExtra=add(plannedExtra,number(item,"planned_extra_amount"));plannedSaving=add(plannedSaving,number(item,"planned_saving_amount"));}
            }
            for(var p:snapshot.payments())if(YearMonth.from(p.paidAt()).equals(month)){actual=Math.addExact(actual,p.amount());extraActual=Math.addExact(extraActual,p.extraPrincipal());}
            for(var s:snapshot.savings())if(YearMonth.from(s.savedAt()).equals(month))savingActual=Math.addExact(savingActual,s.amount());
            var totals=result.putObject("monthly_totals");totals.put("scheduled_repayment",required);totals.put("actual_repayment",actual);totals.put("remaining_required_amount",remaining);totals.put("remaining_amount",remaining);totals.put("completed_count",completed);totals.put("total_count",rows.size());totals.put("actual_extra_amount",extraActual);totals.put("actual_saving_amount",savingActual);put(totals,"planned_extra_amount",plannedExtra);put(totals,"planned_saving_amount",plannedSaving);return result;
        }catch(ArithmeticException|org.springframework.dao.DataAccessException e){throw ApiException.unavailable();}
    }
    public JsonNode item(long user,long dueId) {
        var row=dues.find(user,dueId);var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));var snapshot=loans.loanSnapshot(user,row.source().due().loanId(),today);
        return item(row,snapshot,views(user,YearMonth.from(row.source().due().dueDate()),today).get(row.source().due().loanId()));
    }
    private ObjectNode item(StoredDueProjection row,LoanSources.Snapshot snapshot,PlanPresentationPort.PlanView plan) {
        var due=row.source().due();var state=row.state();var loan=snapshot.loans().stream().filter(l->l.loanId()==due.loanId()).findFirst().orElseThrow(ApiException::unavailable);var month=YearMonth.from(due.dueDate());boolean bullet=loan.method()==RepaymentMethod.BULLET;
        Long extra=plan==null?(bullet?Long.valueOf(0):null):plan.plannedExtraAmount(),saving=plan==null?(!bullet?Long.valueOf(0):null):plan.plannedSavingAmount();long paidExtra=0,actualSaving=0;
        for(var p:snapshot.payments())if(p.loanId()==loan.loanId()&&YearMonth.from(p.paidAt()).equals(month))paidExtra=Math.addExact(paidExtra,p.extraPrincipal());for(var s:snapshot.savings())if(s.loanId()==loan.loanId()&&YearMonth.from(s.savedAt()).equals(month))actualSaving=Math.addExact(actualSaving,s.amount());
        var n=mapper.createObjectNode();n.put("due_item_id",due.id());n.put("loan_id",due.loanId());n.put("due_date",due.dueDate().toString());n.put("required_amount",due.requiredAmount());put(n,"planned_extra_amount",extra);n.put("paid_required_amount",state.paidRequiredAmount());n.put("paid_extra_amount",paidExtra);n.put("outstanding_amount",state.outstandingAmount());n.put("status",state.status().name());n.put("display_status",state.status().name());n.put("product_name",loan.productName());put(n,"total_planned_amount",plan==null||plan.extraMonthlyPayment()==null?null:Math.addExact(due.requiredAmount(),plan.extraMonthlyPayment()));put(n,"remaining_extra_amount",extra==null?null:Math.max(0,extra-paidExtra));n.put("unpaid_confirmed",due.confirmedUnpaid());put(n,"planned_saving_amount",saving);n.put("actual_saving_amount",actualSaving);return n;
    }
    private Map<Long,PlanPresentationPort.PlanView> views(long user,YearMonth month,LocalDate today) {
        var source=plans.getIfAvailable();var budget=budgets.getIfAvailable();if(source==null||budget==null)return Map.of();var overrides=budget.monthlyOverrides(user,month);if(overrides==null)throw ApiException.unavailable();var rows=source.monthViews(user,today,month,overrides);if(rows==null)throw ApiException.unavailable();Map<Long,PlanPresentationPort.PlanView> result=new HashMap<>();for(var p:rows)if(p==null||result.put(p.loanId(),p)!=null)throw ApiException.unavailable();return result;
    }
    private void put(ObjectNode n,String k,Object v){n.set(k,mapper.valueToTree(v));}
    private static Long number(JsonNode n,String k){return n.hasNonNull(k)?n.path(k).longValue():null;}
    private static Long add(Long a,Long b){return a==null||b==null?null:Math.addExact(a,b);}
    public static ApiException invalidMonth(){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_MONTH","월 형식을 확인해 주세요.");}
}
