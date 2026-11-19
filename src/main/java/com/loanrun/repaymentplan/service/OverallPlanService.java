package com.loanrun.repaymentplan.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.repaymentplan.repository.PlanApplicationRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class OverallPlanService {
    private final PlanReadService reads;private final SavedPlanQueryService saved;private final PlanApplicationRepository applications;
    private final LoanSourceRepository loans;private final PlanPreviewService actuals;private final RevisionPort revisions;private final ObjectMapper mapper;private final Clock clock;
    public OverallPlanService(PlanReadService reads,SavedPlanQueryService saved,PlanApplicationRepository applications,LoanSourceRepository loans,PlanPreviewService actuals,RevisionPort revisions,ObjectMapper mapper,Clock clock){this.reads=reads;this.saved=saved;this.applications=applications;this.loans=loans;this.actuals=actuals;this.revisions=revisions;this.mapper=mapper;this.clock=clock;}
    public JsonNode read(long user) {
        var ids=loans.activeLoanIds(user);var current=reads.list(user,PlanType.FREE,null,null);var aggregate=current.path("aggregate");
        var state=ids.size()>1?applications.latest(user):Optional.<PlanApplicationRepository.State>empty();boolean exists=state.map(PlanApplicationRepository.State::exists).orElse(false);
        var result=mapper.createObjectNode();result.put("state",ids.size()<2?"SINGLE_LOAN":exists?"AVAILABLE":"EMPTY");result.put("strategy_code",state.map(PlanApplicationRepository.State::code).orElse("NONE"));
        put(result,"applied_at",state.map(PlanApplicationRepository.State::appliedAt).orElse(null));result.put("needs_review",exists&&state.get().revision()!=revisions.current(user));
        for(var k:List.of("target_payoff_date","projected_payoff_date","remaining_principal","monthly_basic_amount","monthly_extra_amount","monthly_total_amount","estimated_total_interest","estimated_total_fee"))result.set(k,aggregate.path(k));
        result.set("data_revision",current.path("data_revision"));var allocations=result.putArray("allocations");Long saving=0L;
        var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));var amounts=ids.isEmpty()?List.<com.loanrun.common.contract.FinanceModels.PlannedAmount>of():saved.plannedAllocations(user,YearMonth.from(today));
        for(var p:current.path("plans")) {
            long id=p.path("loan_id").longValue();boolean bullet=loans.loan(user,id).method()==com.loanrun.common.contract.FinanceModels.RepaymentMethod.BULLET;
            var a=amounts.stream().filter(v->v.loanId()==id).findFirst();if(a.isEmpty())throw ApiException.unavailable();var n=allocations.addObject();n.put("loan_id",id);n.put("product_name",loans.loan(user,id).productName());n.put("amount",a.get().cashAmount());
            put(n,"priority",exists?a.get().priority():null);n.set("target_payoff_date",p.path("target_payoff_date"));n.set("projected_payoff_date",p.path("projected_payoff_date"));n.put("allocation_type",a.get().allocationType().name());
            if(bullet){n.set("actual_amount",p.path("actual_saving_amount"));saving=Math.addExact(saving,a.get().cashAmount());}else put(n,"actual_amount",actuals.actualExtraCash(user,id,today));
        }
        result.put("monthly_saving_amount",saving);return result;
    }
    private void put(ObjectNode n,String k,Object value){n.set(k,mapper.valueToTree(value));}
}
