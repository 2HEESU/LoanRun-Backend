package com.loanrun.repaymentplan.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.PlanPresentationPort.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.domain.LoanSavingsCalculator;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.repaymentplan.domain.*;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Current individual presentation; shared historical monthViews port is deliberately not partially installed. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PlanViewQueryService {
    private final LoanSourceRepository loans;
    private final PlanPayoffQueryService payoffs;
    private final PlanPerformanceQueryService performance;
    private final PlanSourceQueryService plans;
    private final RepaymentEngine engine;
    private final Clock clock;
    public PlanViewQueryService(LoanSourceRepository loans,PlanPayoffQueryService payoffs,PlanPerformanceQueryService performance,PlanSourceQueryService plans,RepaymentEngine engine,Clock clock) {
        this.loans=loans;this.payoffs=payoffs;this.performance=performance;this.plans=plans;this.engine=engine;this.clock=clock;
    }
    public PlanView current(long user,long id,List<PlannedAmount> overrides,Long recurringCap,Integer priority) {
        return current(user,id,overrides,recurringCap,priority,null);
    }
    /** A monthly caller must not use next-cycle metadata as this month's plan version. */
    public Optional<PlanView> currentForVersion(long user,long id,List<PlannedAmount> overrides,Integer priority,long version) {
        return Optional.ofNullable(current(user,id,overrides,null,priority,version));
    }
    private PlanView current(long user,long id,List<PlannedAmount> overrides,Long recurringCap,Integer priority,Long version) {
        var p=payoffs.current(user,id);var current=p.current();var loan=current.loan();var date=current.asOf();
        if(loan.storedStatus()==LoanStatus.DELETED)throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
        var f=performance.current(user,id);
        if(!date.equals(f.asOf())||!date.equals(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))))throw ApiException.unavailable();
        var source=p.plan()!=null?p.plan():f.plan();
        if(source==null)source=plans.forDate(user,id,loan.storedNextDueDate().isAfter(date)?loan.storedNextDueDate():date,date).orElseThrow(ApiException::unavailable);
        if(version!=null&&source.version()!=version)return null;
        var schedule=new PlanScheduleProjection(engine).throughMonth(source,YearMonth.from(date));
        var perf=f.plan()!=null&&f.plan().version()==source.version()?f.performance():null;
        boolean bullet=loan.method()==RepaymentMethod.BULLET,active=loan.storedStatus()==LoanStatus.ACTIVE;
        var snapshot=loans.loanSnapshot(user,id,date);long assets=LoanSavingsCalculator.balance(loan,snapshot);
        Long basic=active?null:0L;
        if(current.terms()!=null) {
            int months=Math.toIntExact(ChronoUnit.MONTHS.between(YearMonth.from(date),YearMonth.from(current.terms().nextDueDate()))+1);
            var rows=engine.simulate(new SimulationInput(List.of(current.terms()),date,months,Map.of(),Map.of(),null,"NONE")).rows();
            if(!rows.isEmpty())basic=Math.addExact(rows.getFirst().scheduledPrincipal(),rows.getFirst().interest());
        }
        Long plannedExtra=bullet||source.monthlyExtraCash()==0?0L:null;
        Long plannedSaving=bullet?null:0L;
        if(schedule.planned()!=null) {
            long e=0,s=0;
            for(var row:schedule.planned().rows())if(YearMonth.from(row.date()).equals(YearMonth.from(date))) {
                e=Math.addExact(e,row.extraPrincipal());s=Math.addExact(s,row.saving());
            }
            plannedExtra=e;plannedSaving=s;
        } else if(bullet) {
            if(!YearMonth.from(source.effectiveDate()).isAfter(YearMonth.from(date))&&!YearMonth.from(date).isAfter(YearMonth.from(loan.maturityDate())))plannedSaving=source.monthlySaving();
            else plannedSaving=0L;
        }
        PlannedAmount override=null;
        for(var a:overrides)if(a.loanId()==id) {
            if(override!=null||a.cashAmount()<0||a.allocationType()==null||(a.allocationType()==AllocationType.REPAYMENT_SAVING)!=bullet)throw ApiException.unavailable();override=a;
        }
        if(override!=null) {
            if(bullet)plannedSaving=override.cashAmount();
            else {
                plannedExtra=override.cashAmount()==0?0L:null;
                if(current.terms()!=null&&YearMonth.from(current.terms().nextDueDate()).equals(YearMonth.from(date))) {
                    var result=engine.simulate(new SimulationInput(List.of(current.terms()),date,1,
                        Map.of(YearMonth.from(date),List.of(override)),Map.of(),null,"KEEP_PLANS"));
                    if(!result.rows().isEmpty()&&result.rows().getFirst().fee()!=null)plannedExtra=result.rows().getFirst().extraPrincipal();
                }
            }
        }
        long actualExtra=0,actualSaving=0,cumulativeExtra=0,cumulativeSaving=0;
        for(var row:snapshot.payments()) {
            if(!row.paidAt().isBefore(source.effectiveDate()))cumulativeExtra=Math.addExact(cumulativeExtra,row.extraPrincipal());
            if(YearMonth.from(row.paidAt()).equals(YearMonth.from(date)))actualExtra=Math.addExact(actualExtra,row.extraPrincipal());
        }
        for(var row:snapshot.savings()) {
            if(!row.savedAt().isBefore(source.effectiveDate()))cumulativeSaving=Math.addExact(cumulativeSaving,row.amount());
            if(YearMonth.from(row.savedAt()).equals(YearMonth.from(date)))actualSaving=Math.addExact(actualSaving,row.amount());
        }
        var sim=p.plan()!=null&&p.plan().version()==source.version()&&p.projection()!=null?p.projection().simulation():null;
        var payoff=sim==null?null:sim.projectedPayoffDate();var target=source.targetPayoffDate();
        var warnings=new ArrayList<String>();if(sim!=null)warnings.addAll(sim.warnings());
        var storedStatus=perf==null?performance.regularStatus(user,id,loan.createdAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),date):null;
        String regular=perf==null?(storedStatus==null?null:storedStatus.name()):perf.track().regularDueStatus();
        Track track=perf==null?new Track(null,"INSUFFICIENT",regular):perf.track();
        Long extraGap=perf==null?null:perf.monthlyExtra().gap(),savingGap=perf==null?null:perf.cumulativeSaving().gap();
        if(source.effectiveDate().isAfter(YearMonth.from(date).atDay(1)))extraGap=null;
        if(override!=null){track=new Track("UNRECORDED".equals(regular)?"UNRECORDED":null,"INSUFFICIENT",regular);extraGap=null;savingGap=null;}
        Long required=0L;if(bullet)required=target==null?null:LoanSavingsCalculator.required(loan,assets,date,target);
        BigDecimal savingPct=bullet&&loan.storedRemainingPrincipal()>0?BigDecimal.valueOf(assets).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(loan.storedRemainingPrincipal()),2,RoundingMode.HALF_UP):null;
        Long gap=recurringCap==null?null:Math.max(0,Math.subtractExact(Math.addExact(source.monthlyExtraCash(),source.monthlySaving()),Math.max(0,recurringCap)));
        String projectionSource=p.projection()==null?"INSUFFICIENT":p.projection().basis().source().name();
        return new PlanView(id,source.mode(),source.preference(),source.targetDate(),active?source.monthlyExtraCash():0L,
            basic==null?null:Math.addExact(basic,active?source.monthlyExtraCash():0),payoff,sim==null?null:sim.totalInterest(),source.effectiveDate(),target,payoff,
            bullet?"LUMP_SUM":"INSTALLMENT",perf==null?null:perf.plannedProgressPct(),perf==null?null:perf.actualProgressPct(),plannedExtra,actualExtra,extraGap,
            payoff==null||target==null?null:Math.toIntExact(Math.max(0,ChronoUnit.MONTHS.between(YearMonth.from(target),YearMonth.from(payoff)))),track,
            perf==null||override!=null?null:perf.cumulativeExtra().planned(),cumulativeExtra,perf==null||override!=null?null:perf.cumulativeExtra().gap(),priority,
            target==null?null:Math.toIntExact(ChronoUnit.DAYS.between(date,target)),sim==null?null:sim.totalFee(),assets,required,plannedSaving,actualSaving,
            perf==null||override!=null?null:perf.cumulativeSaving().planned(),cumulativeSaving,savingGap,savingPct,projectionSource,
            p.projection()==null?0:p.projection().basis().observedMonthCount(),source.startPrincipal(),regular,
            perf==null||override!=null?null:perf.cumulativeExtra().achievementPct(),gap,warnings);
    }
}
