package com.loanrun.repaymentplan.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.idempotency.IdempotencyService;
import com.loanrun.common.idempotency.IdempotencyService.StoredResponse;
import com.loanrun.common.validation.JsonPatch;
import com.loanrun.loan.service.CurrentLoanTermsQueryService;
import com.loanrun.repaymentplan.domain.PlanVersion.*;
import com.loanrun.repaymentplan.repository.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanSaveService {
    private final IdempotencyService idempotency;private final PlanPreviewService previews;private final CurrentLoanTermsQueryService current;
    private final PlanVersionRepository versions;private final PlanApplicationRepository applications;private final RevisionPort revisions;private final ObjectProvider<BudgetRecalculationPort> budgets;private final ObjectMapper mapper;private final Clock clock;
    public PlanSaveService(IdempotencyService idempotency,PlanPreviewService previews,CurrentLoanTermsQueryService current,PlanVersionRepository versions,PlanApplicationRepository applications,RevisionPort revisions,ObjectProvider<BudgetRecalculationPort> budgets,ObjectMapper mapper,Clock clock){this.idempotency=idempotency;this.previews=previews;this.current=current;this.versions=versions;this.applications=applications;this.revisions=revisions;this.budgets=budgets;this.mapper=mapper;this.clock=clock;}
    @Transactional
    public StoredResponse save(long user,PlanType access,JsonNode body,String key) {
        try{return idempotency.execute(user,"PUT","/api/repayment-plans/",key,body,"IDEMPOTENCY_CONFLICT",()->store(user,access,body));}
        catch(com.loanrun.repaymentplan.domain.PlanVersion.Conflict e){throw stale();}
        catch(org.springframework.dao.DataAccessException e){throw ApiException.unavailable();}
    }
    private StoredResponse store(long user,PlanType access,JsonNode body) {
        JsonPatch.fields(body,"plans","effective_date","report_id","strategy_code","strategy_version","preview_version");String token=JsonPatch.text(body,"preview_version",false,128);
        ObjectNode input=body.deepCopy();input.remove("preview_version");var calculated=previews.preview(user,access,input);
        if(!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),calculated.path("preview_version").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw stale();
        var budget=budgets.getIfAvailable();if(budget==null)throw ApiException.unavailable();long revision=revisions.current(user);boolean strategy=input.has("report_id");
        var state=applications.latest(user);boolean exists=state.map(PlanApplicationRepository.State::exists).orElse(false)||(strategy&&calculated.path("proposed_plans").size()>1);
        String code=strategy?input.path("strategy_code").asText():exists?"MANUAL":"NONE";
        var selected=new HashMap<Long,JsonNode>();if(!strategy)for(var p:input.path("plans"))selected.put(p.path("loan_id").longValue(),p);
        LocalDate first=null;long lastVersion=0;List<PlannedAmount> allocations=new ArrayList<>();
        for(var a:calculated.path("proposed_allocations"))allocations.add(new PlannedAmount(a.path("loan_id").longValue(),AllocationType.valueOf(a.path("allocation_type").asText()),a.path("amount").longValue(),a.path("priority").isNumber()?a.path("priority").intValue():null));
        for(var p:calculated.path("proposed_plans")) {
            long id=p.path("loan_id").longValue();if(!strategy&&!selected.containsKey(id))continue;var raw=selected.get(id);var terms=current.current(user,id).terms();if(terms==null)throw ApiException.unavailable();
            LocalDate effective=LocalDate.parse(p.path("effective_date").asText());if(effective.isBefore(terms.nextDueDate()))throw new ApiException(HttpStatus.BAD_REQUEST,"PLAN_VALIDATION_ERROR","상환 계획을 확인해 주세요.");
            var history=versions.versions(user,id);long expected=history.isEmpty()?1:history.getLast().version();var allocation=allocations.stream().filter(a->a.loanId()==id).findFirst().orElseThrow(ApiException::unavailable);
            boolean bullet=terms.method()==RepaymentMethod.BULLET;var mode=Mode.valueOf(p.path("mode").asText());
            var draft=new Draft(id,mode,p.path("preference").isTextual()?Preference.valueOf(p.path("preference").asText()):null,date(p,"target_date"),date(p,"target_payoff_date"),bullet?0:allocation.cashAmount(),bullet?allocation.cashAmount():0,effective,revision,terms,
                new Inputs(number(raw,"extra_monthly_payment"),number(raw,"monthly_amount"),number(raw,"planned_saving_amount")),strategy?new StrategySource(input.path("report_id").longValue(),input.path("strategy_version").asText(),input.path("strategy_code").asText()):null);
            lastVersion=Math.max(lastVersion,versions.append(user,expected,draft).version());if(first==null||effective.isBefore(first))first=effective;
        }
        if(first==null)throw stale();long next=revisions.bump(user,"REPAYMENT_PLAN_SAVED");applications.append(user,next,exists,code,allocations,clock.instant());budget.recalculateFrom(user,YearMonth.from(first));
        ObjectNode result=mapper.createObjectNode();result.set("plans",calculated.path("proposed_plans"));result.put("effective_date",first.toString());
        for(var k:List.of("estimated_payoff_date","estimated_total_interest","monthly_burden","estimated_total_fee"))result.set(k,calculated.path(k));
        result.put("plan_version","plan-"+next+"-v"+lastVersion);if(access==PlanType.PRO)result.put("strategy_code",code);else result.putNull("strategy_code");result.put("overall_plan_exists",access==PlanType.PRO&&exists);result.set("allocations",calculated.path("proposed_allocations"));
        result.put("data_revision","rev-"+next+"-"+LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))));return new StoredResponse(200,mapper.valueToTree(ApiResponse.success("REPAYMENT_PLAN_SAVE_SUCCESS","상환 계획을 저장했습니다.",result)));
    }
    private static LocalDate date(JsonNode n,String k){return n.hasNonNull(k)?LocalDate.parse(n.path(k).asText()):null;}
    private static Long number(JsonNode n,String k){return n!=null&&n.hasNonNull(k)?n.path(k).longValue():null;}
    private static ApiException stale(){return new ApiException(HttpStatus.CONFLICT,"PLAN_PREVIEW_STALE","대출 또는 재정정보가 바뀌었습니다. 미리보기를 다시 실행해 주세요.");}
}
