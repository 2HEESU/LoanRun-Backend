package com.loanrun.loan.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.payment.service.StoredDueQueryService;
import com.loanrun.repaymentplan.service.PlanReadService;
import java.time.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class LoanDetailService {
    private final LoanListService list;private final PlanReadService plans;private final LoanSourceRepository sources;
    private final StoredDueQueryService dues;private final ObjectProvider<BudgetQueryPort> budgets;private final ObjectMapper mapper;
    public LoanDetailService(LoanListService list,PlanReadService plans,LoanSourceRepository sources,StoredDueQueryService dues,ObjectProvider<BudgetQueryPort> budgets,ObjectMapper mapper) {
        this.list=list;this.plans=plans;this.sources=sources;this.dues=dues;this.budgets=budgets;this.mapper=mapper;
    }
    public JsonNode detail(long user,long id,PlanType access) {
        try {
            var loan=list.one(user,id);ObjectNode data=mapper.valueToTree(loan);
            var all=plans.list(user,access,Long.toString(id),null).path("plans");if(all.size()!=1)throw ApiException.unavailable();var plan=all.get(0);
            data.set("plan",plan);data.set("estimated_remaining_interest",plan.path("estimated_total_interest"));data.set("estimated_total_fee",plan.path("estimated_total_fee"));
            data.set("required_monthly_saving",plan.path("required_monthly_saving"));data.putNull("next_payment");
            if(loan.status()!=LoanStatus.ACTIVE)return data;
            var source=sources.visibleLoan(user,id);var snapshot=sources.visibleLoanSnapshot(user,id,loan.asOf());
            var rows=dues.range(user,new DateRange(source.createdAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),loan.maturityDate()),id);
            for(var row:rows) {
                if(!row.evaluatedOn().equals(loan.asOf()))throw ApiException.unavailable();
                if(row.state().outstandingAmount()==0)continue;var due=row.source().due();var state=row.state();
                boolean sameMonth=YearMonth.from(due.dueDate()).equals(YearMonth.from(loan.asOf()));
                Long target=sameMonth&&plan.path("planned_extra_amount").isNumber()?plan.path("planned_extra_amount").longValue():loan.repaymentMethod()==RepaymentMethod.BULLET?0L:null;
                Long saving=loan.repaymentMethod()!=RepaymentMethod.BULLET?0L:sameMonth&&plan.path("planned_saving_amount").isNumber()?plan.path("planned_saving_amount").longValue():null;
                long paidExtra=0,actualSaving=0;
                for(var p:snapshot.payments())if(YearMonth.from(p.paidAt()).equals(YearMonth.from(due.dueDate())))paidExtra=Math.addExact(paidExtra,p.extraPrincipal());
                for(var s:snapshot.savings())if(YearMonth.from(s.savedAt()).equals(YearMonth.from(due.dueDate())))actualSaving=Math.addExact(actualSaving,s.amount());
                long cash=plan.path("extra_monthly_payment").longValue();
                if(sameMonth) {
                    var budget=budgets.getIfAvailable();if(budget==null)throw ApiException.unavailable();var allocations=budget.monthlyOverrides(user,YearMonth.from(loan.asOf()));
                    if(allocations==null)throw ApiException.unavailable();
                    for(var a:allocations)if(a.loanId()==id&&a.allocationType()==AllocationType.EXTRA_PAYMENT)cash=a.cashAmount();
                }
                ObjectNode next=mapper.createObjectNode();next.put("due_item_id",due.id());next.put("loan_id",id);next.put("due_date",due.dueDate().toString());
                next.put("required_amount",due.requiredAmount());next.set("planned_extra_amount",mapper.valueToTree(target));next.put("paid_required_amount",state.paidRequiredAmount());
                next.put("paid_extra_amount",paidExtra);next.put("outstanding_amount",state.outstandingAmount());next.put("status",state.status().name());next.put("display_status",state.status().name());
                next.put("product_name",loan.productName());next.set("total_planned_amount",mapper.valueToTree(sameMonth?Math.addExact(due.requiredAmount(),cash):null));
                next.set("remaining_extra_amount",mapper.valueToTree(target==null?null:Math.max(0,target-paidExtra)));next.put("unpaid_confirmed",due.confirmedUnpaid());
                next.set("planned_saving_amount",mapper.valueToTree(saving));next.put("actual_saving_amount",actualSaving);data.set("next_payment",next);break;
            }
            return data;
        } catch(ArithmeticException|IllegalArgumentException|DataAccessException e){throw ApiException.unavailable();}
    }
}
