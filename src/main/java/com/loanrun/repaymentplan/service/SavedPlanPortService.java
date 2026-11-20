package com.loanrun.repaymentplan.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.domain.LoanSavingsCalculator;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.repaymentplan.read.RegistrationPlanFallback;
import com.loanrun.repaymentplan.repository.PlanApplicationRepository;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Complete shared saved-plan reads; no budget calls, monthly override mutation or DB activation. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class SavedPlanPortService implements PlanQueryPort,RegistrationPlanFallback {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private final LoanSourceRepository loans;
    private final LoanQueryPort loanQueries;
    private final PlanSourceQueryService sources;
    private final PlanPayoffQueryService payoffs;
    private final PlanApplicationRepository applications;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public SavedPlanPortService(LoanSourceRepository loans,LoanQueryPort loanQueries,PlanSourceQueryService sources,
        PlanPayoffQueryService payoffs,PlanApplicationRepository applications,JdbcTemplate jdbc,Clock clock) {
        this.loans=loans;this.loanQueries=loanQueries;this.sources=sources;this.payoffs=payoffs;
        this.applications=applications;this.jdbc=jdbc;this.clock=clock;
    }
    @Override public boolean registrationFallbackAllowed() {
        return read(()->Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT to_regclass('repayment_plan_versions') IS NULL AND to_regclass('plan_application_events') IS NULL
                AND NOT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='4' AND success)
            """,Boolean.class)));
    }
    @Override public List<PlanSnapshot> currentPlans(long user,LocalDate asOf) {
        return read(()->{
            owner(user);Objects.requireNonNull(asOf);var now=clock.instant();var today=now.atZone(KST).toLocalDate();
            if(asOf.isAfter(today))throw ApiException.unavailable();
            var ids=asOf.equals(today)?loans.activeLoanIds(user):loanQueries.activeLoans(user,asOf).stream().map(LoanTerms::loanId).toList();
            return currentForIds(user,asOf,now,today,ids);
        });
    }
    @Override public PlanSnapshot currentPlan(long user,long id,LocalDate asOf) {
        return read(()->{
            owner(user);Objects.requireNonNull(asOf);var now=clock.instant();var today=now.atZone(KST).toLocalDate();
            var loan=loans.loan(user,id);
            if(!asOf.equals(today)||loan.storedStatus()!=LoanStatus.ACTIVE)throw ApiException.unavailable();
            return currentForIds(user,asOf,now,today,List.of(id)).getFirst();
        });
    }
    private List<PlanSnapshot> currentForIds(long user,LocalDate asOf,Instant now,LocalDate today,List<Long> ids) {
            var result=new ArrayList<PlanSnapshot>();
            for(long id:ids) {
                var loan=loans.loan(user,id);var forecast=asOf.equals(today)?payoffs.current(user,id):null;
                if(forecast!=null&&!forecast.current().asOf().equals(asOf))throw ApiException.unavailable();
                var next=forecast!=null&&forecast.current().terms()!=null?forecast.current().terms().nextDueDate():loan.storedNextDueDate();
                var date=next.isAfter(asOf)?next:asOf;
                var s=sources.forDate(user,id,date,asOf).orElseThrow(ApiException::unavailable);
                if(asOf.equals(today)&&s.recordedAt().isAfter(now))throw ApiException.unavailable();
                long required=0;
                if(loan.method()==RepaymentMethod.BULLET)required=LoanSavingsCalculator.required(loan,
                    LoanSavingsCalculator.balance(loan,loans.loanSnapshot(user,id,asOf)),asOf,s.targetPayoffDate());
                LocalDate projected=null;
                if(forecast!=null&&forecast.plan()!=null&&forecast.plan().version()==s.version()&&forecast.projection()!=null&&forecast.projection().simulation()!=null)
                    projected=forecast.projection().simulation().projectedPayoffDate();
                result.add(new PlanSnapshot(id,s.configured(),s.targetPayoffDate(),projected,s.monthlyExtraCash(),s.monthlySaving(),required,s.mode(),s.preference(),s.effectiveDate(),s.version()));
            }
            return List.copyOf(result);
    }
    @Override public List<PlannedAmount> plannedAllocations(long user,YearMonth month) {
        return read(()->{
            owner(user);Objects.requireNonNull(month);var now=clock.instant();var today=now.atZone(KST).toLocalDate();
            boolean past=month.isBefore(YearMonth.from(today));
            var ids=past?loanQueries.historicalLoans(user,new DateRange(month.atDay(1),month.atEndOfMonth())).stream()
                .filter(l->l.status()==LoanStatus.ACTIVE).map(LoanTerms::loanId).toList():loans.activeLoanIds(user);
            if(ids.isEmpty())return List.of();
            var known=past?month.atEndOfMonth():today;
            var cutoff=past?known.plusDays(1).atStartOfDay(KST).toInstant().minusNanos(1):now;
            var state=applications.latestBefore(user,cutoff);var result=new ArrayList<PlannedAmount>();
            for(long id:ids) {
                var loan=loans.loan(user,id);if(loan.maturityDate().isBefore(month.atDay(1)))continue;
                var selected=sources.forDate(user,id,month.atEndOfMonth(),known);if(selected.isEmpty())continue;
                var s=selected.get();if(s.recordedAt().isAfter(cutoff))throw ApiException.unavailable();
                Integer priority=state.flatMap(v->v.allocations().stream().filter(a->a.loanId()==id).findFirst()).map(PlannedAmount::priority).orElse(null);
                boolean bullet=loan.method()==RepaymentMethod.BULLET;
                result.add(new PlannedAmount(id,bullet?AllocationType.REPAYMENT_SAVING:AllocationType.EXTRA_PAYMENT,bullet?s.monthlySaving():s.monthlyExtraCash(),priority));
            }
            return List.copyOf(result);
        });
    }
    @Override public OverallState overallState(long user) {
        return read(()->{
            owner(user);return applications.latestBefore(user,clock.instant()).map(s->new OverallState(s.exists(),s.code(),s.code(),s.appliedAt()))
                .orElse(new OverallState(false,"NONE","NONE",null));
        });
    }
    private static void owner(long user){if(user<=0)throw new IllegalArgumentException("Positive internal owner required");}
    private static <T>T read(Supplier<T> query){try{return query.get();}catch(ArithmeticException|DataAccessException e){throw ApiException.unavailable();}}
}
