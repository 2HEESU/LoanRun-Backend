package com.loanrun.repaymentplan.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.validation.JsonPatch;
import com.loanrun.finance.engine.*;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.service.CurrentLoanTermsQueryService;
import com.loanrun.repaymentplan.domain.SavedPlanSource;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Read-only coordinator: candidates are compared with identical current terms and pinned monthly goals. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PlanPreviewService {
    private final LoanSourceRepository loans;private final CurrentLoanTermsQueryService current;private final PlanSourceQueryService sources;
    private final PlanReadService reads;private final RepaymentEngine engine;private final ObjectProvider<BudgetQueryPort> budgets;
    private final ObjectProvider<StrategyQueryPort> strategies;private final RevisionPort revisions;private final ObjectMapper mapper;private final Clock clock;
    public PlanPreviewService(LoanSourceRepository loans,CurrentLoanTermsQueryService current,PlanSourceQueryService sources,PlanReadService reads,RepaymentEngine engine,ObjectProvider<BudgetQueryPort> budgets,ObjectProvider<StrategyQueryPort> strategies,RevisionPort revisions,ObjectMapper mapper,Clock clock) {
        this.loans=loans;this.current=current;this.sources=sources;this.reads=reads;this.engine=engine;this.budgets=budgets;this.strategies=strategies;this.revisions=revisions;this.mapper=mapper;this.clock=clock;
    }
    private record Goal(LoanTerms terms,String mode,String preference,LocalDate target,LocalDate effective,long extra,long saving,Integer priority,boolean edited) {
        long amount(){return Math.addExact(extra,saving);}
        Goal amounts(long extra,long saving){return new Goal(terms,mode,preference,target,effective,extra,saving,priority,edited);}
    }
    public JsonNode preview(long user,PlanType access,JsonNode body) {
        try{return calculate(user,access,body);}
        catch(ApiException e){if(e.code().equals("VALIDATION_ERROR"))throw new ApiException(HttpStatus.BAD_REQUEST,"PLAN_VALIDATION_ERROR","상환 계획을 확인해 주세요.",e.errors());throw e;}
        catch(ArithmeticException e){throw validation("non_field_errors");}
        catch(org.springframework.dao.DataAccessException e){throw ApiException.unavailable();}
    }
    private JsonNode calculate(long user,PlanType access,JsonNode body) {
        JsonPatch.fields(body,"plans","report_id","strategy_code","strategy_version","effective_date");
        boolean strategy=body.has("report_id")||body.has("strategy_code")||body.has("strategy_version");
        if(strategy&&body.has("plans")||!strategy&&(!body.has("plans")||!body.path("plans").isArray()||body.path("plans").isEmpty()))throw validation("plans");
        if(strategy&&access!=PlanType.PRO)throw new ApiException(HttpStatus.FORBIDDEN,"PRO_REQUIRED","PRO 이용자만 사용할 수 있습니다.");
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));LocalDate effective=date(body,"effective_date",false);
        if(effective!=null&&effective.isBefore(today))throw validation("effective_date");
        var ids=loans.activeLoanIds(user);if(ids.isEmpty())throw missing();
        // Validate requested ownership before reading any other household calculation basis.
        Map<Long,JsonNode> requests=new LinkedHashMap<>();
        if(!strategy)for(var p:body.path("plans")) {
            JsonPatch.fields(p,"loan_id","mode","preference","target_date","extra_monthly_payment","monthly_amount","planned_saving_amount");long id=amount(p,"loan_id",true);
            if(!ids.contains(id))throw missing();if(requests.put(id,p)!=null)throw validation("loan_id");
        }
        var budget=budgets.getIfAvailable();if(budget==null)throw ApiException.unavailable();var capacity=budget.capacity(user,YearMonth.from(today));
        if(capacity==null||!capacity.month().equals(YearMonth.from(today))||capacity.recurringCap()==null||capacity.recurringCap()<0)throw ApiException.unavailable();long cap=capacity.recurringCap();
        var currentData=reads.list(user,access,null,null);Map<Long,JsonNode> views=new LinkedHashMap<>();for(var p:currentData.path("plans"))views.put(p.path("loan_id").longValue(),p);
        List<Goal> before=new ArrayList<>(),after=new ArrayList<>();Map<Long,JsonNode> preferenceRequests=new LinkedHashMap<>();
        for(long id:ids) {
            var basis=current.current(user,id);var t=basis.terms();if(t==null)throw ApiException.unavailable();
            var saved=sources.forDate(user,id,t.nextDueDate(),today).orElseThrow(ApiException::unavailable);
            Integer priority=views.get(id).path("priority").isNumber()?views.get(id).path("priority").intValue():null;
            var old=new Goal(t,saved.mode(),saved.preference(),saved.targetPayoffDate(),saved.effectiveDate(),saved.monthlyExtraCash(),saved.monthlySaving(),priority,false);before.add(old);
            if(!strategy&&!requests.containsKey(id)){after.add(new Goal(t,old.mode(),old.preference(),old.target(),old.effective(),old.extra(),old.saving(),null,false));continue;}
            LocalDate start=effective==null?t.nextDueDate():effective;
            if(start.isAfter(t.maturityDate()))throw validation("effective_date");
            if(RepaymentDates.remaining(t,today).stream().anyMatch(d->d.isBefore(start)))throw ApiException.unavailable();
            if(strategy){after.add(new Goal(t,"STRATEGY",null,t.maturityDate(),start,0,0,null,true));continue;}
            var p=requests.get(id);String mode=JsonPatch.text(p,"mode",false,40);if(!Set.of("MATURITY","PREFERENCE","TARGET_DATE","EXTRA_AMOUNT","MONTHLY_AMOUNT").contains(mode))throw validation("mode");
            boolean bullet=t.method()==RepaymentMethod.BULLET;String preference=null;long extra=0,saving=0;LocalDate target=date(p,"target_date",mode.equals("TARGET_DATE"));
            if(target!=null&&(target.isBefore(start)||target.isAfter(t.maturityDate())))throw notFeasible();
            if(!bullet&&!mode.equals("TARGET_DATE")&&target!=null)throw validation("target_date");
            if(!bullet&&mode.equals("MATURITY"))target=t.maturityDate();
            if(!mode.equals("PREFERENCE")&&p.hasNonNull("preference"))throw validation("preference");
            if(!mode.equals("MONTHLY_AMOUNT")&&p.hasNonNull("monthly_amount"))throw validation("monthly_amount");
            if(!Set.of("EXTRA_AMOUNT","PREFERENCE").contains(mode)&&p.hasNonNull("extra_monthly_payment"))throw validation("extra_monthly_payment");
            if(!bullet&&p.hasNonNull("planned_saving_amount"))throw validation("planned_saving_amount");
            if(bullet) {
                if(!Set.of("MATURITY","TARGET_DATE").contains(mode))throw validation("mode");target=target==null?t.maturityDate():target;
                var inverse=engine.minimumExtra(new TargetInput(input(List.of(new Goal(t,mode,null,target,start,0,0,null,true)),today,"KEEP_PLANS"),id,target));
                if(!inverse.feasible()||inverse.minimumMonthlyExtraCash()==null)throw notFeasible();
                saving=p.hasNonNull("planned_saving_amount")?amount(p,"planned_saving_amount",true):inverse.minimumMonthlyExtraCash();
            } else if(mode.equals("PREFERENCE")) {
                preference=JsonPatch.text(p,"preference",false,30);if(!Set.of("FAST","BALANCED","STABLE").contains(preference))throw validation("preference");preferenceRequests.put(id,p);
            } else if(mode.equals("EXTRA_AMOUNT"))extra=amount(p,"extra_monthly_payment",true);
            else if(mode.equals("MONTHLY_AMOUNT")) {
                long monthly=amount(p,"monthly_amount",true);var base=engine.simulate(new SimulationInput(List.of(t),today,1200,Map.of(),Map.of(),null,"NONE"));if(base.rows().isEmpty())throw notFeasible();
                long basic=Math.addExact(base.rows().getFirst().scheduledPrincipal(),base.rows().getFirst().interest());if(monthly<basic)throw notFeasible();extra=monthly-basic;
            } else if(mode.equals("TARGET_DATE")) {
                var inverse=engine.minimumExtra(new TargetInput(input(List.of(new Goal(t,mode,null,target,start,0,0,null,true)),today,"KEEP_PLANS"),id,target));
                if(!inverse.feasible()||inverse.minimumMonthlyExtraCash()==null)throw notFeasible();extra=inverse.minimumMonthlyExtraCash();
            }
            if(!bullet&&t.method()==RepaymentMethod.GRACE_INSTALLMENT&&t.nextDueDate().isBefore(t.graceEnd())&&extra>0)throw validation("extra_monthly_payment");
            after.add(new Goal(t,mode,preference,target,start,extra,saving,null,true));
        }
        if(!preferenceRequests.isEmpty())allocatePreference(after,preferenceRequests,cap);
        String code=access==PlanType.PRO?(ids.size()>1?"MANUAL":"NONE"):null,version=null;String rule="KEEP_PLANS";
        if(strategy) {
            long report=amount(body,"report_id",true);if(report<=0)throw validation("report_id");code=JsonPatch.text(body,"strategy_code",false,40);version=JsonPatch.text(body,"strategy_version",false,256);
            var allowedCodes=ids.size()==1?(after.getFirst().terms().method()==RepaymentMethod.BULLET?Set.of("SAVINGS"):Set.of("FAST","BALANCED","STABLE")):Set.of("RATE_RISK","INTEREST_SAVING","PARALLEL","SAVINGS");
            if(!allowedCodes.contains(code))throw validation("strategy_code");
            var provider=strategies.getIfAvailable();if(provider==null)throw ApiException.unavailable();long revision=revisions.current(user);var candidate=provider.requireCurrentCandidate(user,report,version,code,revision);
            if(candidate==null||candidate.reportId()!=report||candidate.dataRevision()!=revision||!code.equals(candidate.code())||!version.equals(candidate.strategyVersion())||candidate.computedAt()==null||candidate.computedAt().isAfter(clock.instant())||!today.equals(candidate.computedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate()))throw stale();
            if(candidate.simulation()==null)throw ApiException.unavailable();
            if(!candidate.feasible())throw notFeasible();var amounts=new HashMap<Long,PlannedAmount>();var priorities=new HashSet<Integer>();
            for(var a:candidate.allocations())if(a==null||!ids.contains(a.loanId())||a.cashAmount()<0||amounts.put(a.loanId(),a)!=null||a.priority()!=null&&(a.priority()<1||!priorities.add(a.priority())))throw ApiException.unavailable();
            for(int i=0;i<after.size();i++) {var g=after.get(i);boolean bullet=g.terms().method()==RepaymentMethod.BULLET;var type=bullet?AllocationType.REPAYMENT_SAVING:AllocationType.EXTRA_PAYMENT;
                var a=amounts.getOrDefault(g.terms().loanId(),new PlannedAmount(g.terms().loanId(),type,0,null));
                if(a.allocationType()!=type||ids.size()>1&&!bullet&&a.cashAmount()>0&&a.priority()==null)throw ApiException.unavailable();
                after.set(i,new Goal(g.terms(),"STRATEGY",null,bullet?g.terms().maturityDate():null,g.effective(),bullet?0:a.cashAmount(),bullet?a.cashAmount():0,a.priority(),true));
            }
            rule=code;long sum=sum(after);if(capacity.allocationCapacity()==null||sum>cap||sum>capacity.allocationCapacity())throw validation("strategy_code");
        }
        for(var g:after)if(g.terms().method()==RepaymentMethod.GRACE_INSTALLMENT&&g.terms().nextDueDate().isBefore(g.terms().graceEnd())&&g.extra()>0)throw validation("extra_monthly_payment");
        String oldCode=currentData.path("strategy_code").isTextual()?currentData.path("strategy_code").asText():null;
        String oldRule=oldCode!=null&&Set.of("RATE_RISK","INTEREST_SAVING","PARALLEL").contains(oldCode)?oldCode:"KEEP_PLANS";
        var baseline=engine.simulate(input(before,today,oldRule));var proposed=engine.simulate(input(after,today,rule));
        if(strategy&&proposed.insufficient())throw notFeasible();
        for(var r:List.of(baseline,proposed))if(r.warnings().contains("CONTRACTUAL_AMOUNT_BELOW_INTEREST")||r.warnings().contains("BASIC_PAYMENT_BELOW_INTEREST"))throw notFeasible();
        var warnings=new LinkedHashSet<>(proposed.warnings());long total=sum(after);if(total>cap)warnings.add("RECURRING_CAP_EXCEEDED");
        for(var g:after)if(g.edited()&&g.terms().method()==RepaymentMethod.BULLET&&g.target()!=null) {
            long periods=RepaymentDates.remaining(g.terms(),today).stream().filter(d->!d.isAfter(g.target())).count();
            if(BigDecimal.valueOf(g.saving()).multiply(BigDecimal.valueOf(periods)).add(BigDecimal.valueOf(g.terms().currentSavingBalance())).compareTo(BigDecimal.valueOf(g.terms().outstanding()))<0)warnings.add("TARGET_NOT_MET");
        }
        ObjectNode data=mapper.createObjectNode();var original=data.putArray("current_plans");var proposals=data.putArray("proposed_plans");var currentAlloc=data.putArray("current_allocations");var proposedAlloc=data.putArray("proposed_allocations");
        var monthlyOverrides=budget.monthlyOverrides(user,YearMonth.from(today));if(monthlyOverrides==null)throw ApiException.unavailable();
        long burdenBefore=0,burdenAfter=0;
        for(int i=0;i<before.size();i++) {
            var a=before.get(i);var b=after.get(i);var raw=views.get(a.terms().loanId());var oldView=present(raw,a,baseline,today,false,ids.size()==1?cap:null);var newView=present(raw,b,proposed,today,b.edited(),ids.size()==1?cap:null);
            if(b.edited()) {
                var request=requests.get(b.terms().loanId());newView.set("target_date",request!=null&&request.has("target_date")?request.get("target_date"):NullNode.instance);
                if(monthlyOverrides.stream().anyMatch(o->o.loanId()==b.terms().loanId())){newView.set("planned_extra_amount",raw.path("planned_extra_amount"));newView.set("planned_saving_amount",raw.path("planned_saving_amount"));}
            }
            original.add(oldView);proposals.add(newView);Long actualCash=actualExtraCash(user,a.terms().loanId(),today);allocation(currentAlloc,a,raw,actualCash);allocation(proposedAlloc,b,raw,actualCash);
            burdenBefore=Math.addExact(burdenBefore,burden(a,baseline));burdenAfter=Math.addExact(burdenAfter,burden(b,proposed));
        }
        put(data,"estimated_payoff_date",proposed.projectedPayoffDate());put(data,"estimated_total_interest",proposed.totalInterest());put(data,"estimated_total_fee",proposed.totalFee());data.put("monthly_burden",burdenAfter);
        var delta=data.putObject("delta");put(delta,"payoff_months_saved",baseline.projectedPayoffDate()==null||proposed.projectedPayoffDate()==null?null:ChronoUnit.MONTHS.between(YearMonth.from(proposed.projectedPayoffDate()),YearMonth.from(baseline.projectedPayoffDate())));
        put(delta,"interest_saving",baseline.totalInterest()==null||proposed.totalInterest()==null?null:Math.subtractExact(baseline.totalInterest(),proposed.totalInterest()));delta.put("monthly_burden_delta",Math.subtractExact(burdenAfter,burdenBefore));
        data.set("warnings",mapper.valueToTree(warnings));put(data,"strategy_code",code);put(data,"strategy_version",version);put(data,"current_strategy_code",oldCode);
        String revision="rev-"+revisions.current(user)+"-"+today;data.put("data_revision",revision);data.put("preview_version",hash(user,revision,body));return data;
    }
    private void allocatePreference(List<Goal> goals,Map<Long,JsonNode> requests,long cap) {
        var prefs=goals.stream().filter(g->requests.containsKey(g.terms().loanId())).toList();String preference=prefs.getFirst().preference();if(prefs.stream().anyMatch(g->!preference.equals(g.preference())))throw validation("preference");
        int percent=switch(preference){case "FAST"->80;case "BALANCED"->50;default->0;};long pool=BigDecimal.valueOf(cap).multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100),0,RoundingMode.HALF_UP).longValueExact();
        long other=goals.stream().filter(g->!requests.containsKey(g.terms().loanId())).mapToLong(Goal::amount).reduce(0,Math::addExact);long remaining=Math.max(0,pool-other),assigned=0;
        for(int i=0;i<goals.size();i++){var g=goals.get(i);var p=requests.get(g.terms().loanId());if(p==null)continue;long extra=prefs.size()==1&&!p.hasNonNull("extra_monthly_payment")?remaining:amount(p,"extra_monthly_payment",true);assigned=Math.addExact(assigned,extra);goals.set(i,g.amounts(extra,0));}
        if(assigned!=remaining)throw validation("extra_monthly_payment");
    }
    private SimulationInput input(List<Goal> goals,LocalDate today,String rule) {
        Map<YearMonth,List<PlannedAmount>> amounts=new HashMap<>();for(var g:goals)for(var d:RepaymentDates.remaining(g.terms(),today))if(!d.isBefore(g.effective()))
            amounts.computeIfAbsent(YearMonth.from(d),k->new ArrayList<>()).add(new PlannedAmount(g.terms().loanId(),g.terms().method()==RepaymentMethod.BULLET?AllocationType.REPAYMENT_SAVING:AllocationType.EXTRA_PAYMENT,g.amount(),g.priority()));
        return new SimulationInput(goals.stream().map(Goal::terms).toList(),today,1200,amounts,Map.of(),null,rule);
    }
    private ObjectNode present(JsonNode raw,Goal g,SimulationResult simulation,LocalDate today,boolean edited,Long cap) {
        var n=(ObjectNode)raw.deepCopy();var rows=simulation.rows().stream().filter(r->r.loanId()==g.terms().loanId()).toList();if(rows.isEmpty())throw ApiException.unavailable();var first=rows.getFirst();long interest=0;Long fee=0L;
        for(var r:rows){interest=Math.addExact(interest,r.interest());fee=fee==null||r.fee()==null?null:Math.addExact(fee,r.fee());}
        boolean bullet=g.terms().method()==RepaymentMethod.BULLET;
        long funding=g.terms().currentSavingBalance();if(bullet)for(var r:rows)funding=Math.addExact(funding,r.saving());
        LocalDate payoff=rows.getLast().balance()==0&&(!bullet||funding>=g.terms().outstanding())?rows.getLast().date():null;
        n.put("mode",g.mode());put(n,"preference",g.preference());n.put("extra_monthly_payment",g.extra());n.put("monthly_amount",Math.addExact(Math.addExact(first.scheduledPrincipal(),first.interest()),g.extra()));
        put(n,"estimated_payoff_date",payoff);put(n,"projected_payoff_date",payoff);n.put("estimated_total_interest",interest);put(n,"estimated_total_fee",fee);put(n,"effective_date",g.effective());put(n,"priority",g.priority());
        LocalDate target=edited?(g.target()==null?payoff:g.target()):g.target();put(n,"target_payoff_date",target);put(n,"d_day",target==null?null:ChronoUnit.DAYS.between(today,target));put(n,"payoff_delay_months",target==null||payoff==null?null:Math.max(0,ChronoUnit.MONTHS.between(YearMonth.from(target),YearMonth.from(payoff))));
        n.put("projection_source","FALLBACK_PLAN");n.put("observed_month_count",0);put(n,"affordability_gap_amount",cap==null?null:Math.max(0,g.amount()-cap));n.set("warnings",mapper.valueToTree(simulation.warnings()));
        if(edited) {
            n.put("plan_start_principal",g.terms().outstanding());for(var key:List.of("planned_progress_pct","actual_progress_pct","extra_gap_amount","cumulative_planned_extra_amount","cumulative_actual_extra_amount","cumulative_extra_gap_amount","cumulative_planned_saving_amount","cumulative_actual_saving_amount","saving_gap_amount","extra_achievement_pct"))n.putNull(key);
            var track=n.putObject("track");track.putNull("status");track.put("data_status","INSUFFICIENT");track.set("regular_due_status",raw.path("regular_due_status"));
            boolean currentMonth=YearMonth.from(first.date()).equals(YearMonth.from(today));Long plannedExtra=0L;
            if(currentMonth)plannedExtra=first.fee()==null&&g.extra()>0?null:first.extraPrincipal();
            put(n,"planned_extra_amount",plannedExtra);n.put("planned_saving_amount",currentMonth?first.saving():0L);
        }
        if(bullet) {LocalDate requiredBy=target==null?g.terms().maturityDate():target;int periods=(int)RepaymentDates.remaining(g.terms(),today).stream().filter(d->!d.isAfter(requiredBy)).count();n.put("required_monthly_saving",RepaymentMath.requiredSaving(g.terms().outstanding(),g.terms().currentSavingBalance(),periods));}
        return n;
    }
    private void allocation(ArrayNode a,Goal g,JsonNode raw,Long actualCash) {var n=a.addObject();n.put("loan_id",g.terms().loanId());n.put("amount",g.amount());put(n,"priority",g.priority());boolean bullet=g.terms().method()==RepaymentMethod.BULLET;n.put("allocation_type",bullet?"REPAYMENT_SAVING":"EXTRA_PAYMENT");
        if(bullet)n.set("actual_amount",raw.path("actual_saving_amount"));else put(n,"actual_amount",actualCash);
    }
    Long actualExtraCash(long user,long id,LocalDate today) {
        long cash=0;for(var p:loans.loanSnapshot(user,id,today).payments()) {
            if(!YearMonth.from(p.paidAt()).equals(YearMonth.from(today))||p.extraPrincipal()==0)continue;
            if(p.kind()==com.loanrun.loan.read.LoanSources.PaymentKind.EXTRA&&p.regularPrincipal()==0)cash=Math.addExact(cash,p.amount()-p.savingsUsed());
            else {if(p.fee()!=0||p.savingsUsed()!=0)return null;cash=Math.addExact(cash,p.extraPrincipal());}
        }
        return cash;
    }
    private static long sum(List<Goal> goals){return goals.stream().mapToLong(Goal::amount).reduce(0,Math::addExact);}
    private static long burden(Goal g,SimulationResult simulation) {
        var first=simulation.rows().stream().filter(r->r.loanId()==g.terms().loanId()).findFirst().orElseThrow(ApiException::unavailable);
        long used=g.terms().method()==RepaymentMethod.BULLET?Math.min(first.scheduledPrincipal(),Math.addExact(g.terms().currentSavingBalance(),first.saving())):0;
        return Math.addExact(Math.subtractExact(Math.addExact(first.scheduledPrincipal(),first.interest()),used),Math.addExact(g.extra(),first.saving()));
    }
    private static long amount(JsonNode p,String key,boolean required) {var n=p.get(key);if(!required&&(n==null||n.isNull()))return 0;if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0)throw validation(key);return n.longValue();}
    private static LocalDate date(JsonNode p,String key,boolean required) {if(!p.hasNonNull(key)){if(required)throw validation(key);return null;}try {String s=JsonPatch.text(p,key,false,10);if(!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||s.startsWith("0000"))throw new DateTimeException("date");return LocalDate.parse(s);}catch(DateTimeException e){throw validation(key);}}
    private void put(ObjectNode n,String k,Object v){n.set(k,mapper.valueToTree(v));}
    private JsonNode canonical(JsonNode n){if(n.isObject()){var sorted=mapper.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);for(var k:keys)sorted.set(k,canonical(n.get(k)));return sorted;}if(n.isArray()){var a=mapper.createArrayNode();for(var v:n)a.add(canonical(v));return a;}return n;}
    private String hash(long user,String revision,JsonNode body) {try{return "plan-preview-v1-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((user+"|"+revision+"|"+canonical(body)).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable",e);}}
    private static ApiException validation(String field){return new ApiException(HttpStatus.BAD_REQUEST,"PLAN_VALIDATION_ERROR","상환 계획을 확인해 주세요.",Map.of(field,List.of("입력값과 배분 조건을 확인해 주세요.")));}
    private static ApiException notFeasible(){return new ApiException(HttpStatus.BAD_REQUEST,"PAYOFF_NOT_FEASIBLE","입력한 목표 또는 납부액으로 완납 계획을 계산할 수 없습니다.");}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");}
    private static ApiException stale(){return new ApiException(HttpStatus.CONFLICT,"STRATEGY_STALE","추천 전략 기준 데이터가 변경되었습니다. 다시 조회해 주세요.");}
}
