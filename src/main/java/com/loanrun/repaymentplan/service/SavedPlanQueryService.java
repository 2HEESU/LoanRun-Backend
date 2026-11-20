package com.loanrun.repaymentplan.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.repaymentplan.repository.PlanApplicationRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal saved goals only; runtime common adapter awaits the coordinated DB baseline. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class SavedPlanQueryService {
    private final LoanSourceRepository loans;private final PlanSourceQueryService sources;private final PlanApplicationRepository applications;private final Clock clock;
    public SavedPlanQueryService(LoanSourceRepository loans,PlanSourceQueryService sources,PlanApplicationRepository applications,Clock clock){this.loans=loans;this.sources=sources;this.applications=applications;this.clock=clock;}
    public List<PlanSnapshot> currentPlans(long user,LocalDate asOf) {
        var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));if(asOf==null||!asOf.equals(today))throw ApiException.unavailable();
        var result=new ArrayList<PlanSnapshot>();for(long id:loans.activeLoanIds(user)) {
            var loan=loans.loan(user,id);var date=loan.storedNextDueDate().isBefore(today)?today:loan.storedNextDueDate();
            var s=sources.forDate(user,id,date,today).orElseThrow(ApiException::unavailable);
            result.add(new PlanSnapshot(id,s.configured(),s.targetPayoffDate(),null,s.monthlyExtraCash(),s.monthlySaving(),s.monthlySaving(),s.mode(),s.preference(),s.effectiveDate(),s.version()));
        }return List.copyOf(result);
    }
    public List<PlannedAmount> plannedAllocations(long user,YearMonth month) {
        var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));if(month==null||month.isBefore(YearMonth.from(today)))throw ApiException.unavailable();
        var state=applications.latest(user);var result=new ArrayList<PlannedAmount>();
        for(long id:loans.activeLoanIds(user)) {
            var l=loans.loan(user,id);if(l.maturityDate().isBefore(month.atDay(1)))continue;
            var s=sources.forDate(user,id,month.atEndOfMonth(),today);if(s.isEmpty())continue;
            Integer priority=state.flatMap(v->v.allocations().stream().filter(a->a.loanId()==id).findFirst()).map(PlannedAmount::priority).orElse(null);
            boolean bullet=l.method()==RepaymentMethod.BULLET;
            result.add(new PlannedAmount(id,bullet?AllocationType.REPAYMENT_SAVING:AllocationType.EXTRA_PAYMENT,bullet?s.get().monthlySaving():s.get().monthlyExtraCash(),priority));
        }return List.copyOf(result);
    }
    public OverallState overallState(long user) {return applications.latest(user).map(s->new OverallState(s.exists(),s.code(),s.code(),s.appliedAt())).orElse(new OverallState(false,"NONE","NONE",null));}
}
