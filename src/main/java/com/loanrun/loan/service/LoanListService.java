package com.loanrun.loan.service;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.RepaymentEngine;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.domain.LoanSavingsCalculator;
import com.loanrun.loan.domain.LoanTermMapper;
import com.loanrun.loan.dto.*;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.read.LoanSources.*;
import com.loanrun.repaymentplan.domain.SavedPlanSource;
import com.loanrun.repaymentplan.service.PlanPayoffQueryService;
import com.loanrun.repaymentplan.service.PlanSourceQueryService;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Current materialized loan balances are authoritative; actual payments must not be subtracted twice. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class LoanListService {
    private final LoanSourceRepository sources;
    private final RepaymentEngine engine;
    private final ObjectProvider<PlanPayoffQueryService> payoffs;
    private final ObjectProvider<PlanSourceQueryService> plans;
    private final Clock clock;
    public LoanListService(LoanSourceRepository sources,RepaymentEngine engine,
            ObjectProvider<PlanPayoffQueryService> payoffs,ObjectProvider<PlanSourceQueryService> plans,Clock clock) {
        this.sources=sources;this.engine=engine;this.payoffs=payoffs;this.plans=plans;this.clock=clock;
    }

    public LoanList list(long userId,String status) {
        if(!Set.of("ACTIVE","PAID","ALL").contains(status))throw ApiException.validation("status","입력값이 올바르지 않습니다.");
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var snapshot=sources.visibleSnapshot(userId,today,status);
        try {
            var result=new ArrayList<LoanCreated>();long balance=0,due=0;
            LocalDate overallEstimatedPayoff=null;boolean anyActive=false,allActiveKnown=true;
            for(var loan:snapshot.loans()) {
                var view=view(userId,loan,snapshot,today);result.add(view.loan());
                if(loan.storedStatus()==LoanStatus.ACTIVE) {
                    anyActive=true;
                    balance=Math.addExact(balance,loan.storedRemainingPrincipal());
                    due=Math.addExact(due,view.required());
                    var loanPayoff=view.loan().estimatedPayoffDate();
                    if(loanPayoff==null)allActiveKnown=false;
                    else if(overallEstimatedPayoff==null||loanPayoff.isAfter(overallEstimatedPayoff))overallEstimatedPayoff=loanPayoff;
                }
            }
            return new LoanList(result,balance,due,anyActive&&allActiveKnown?overallEstimatedPayoff:null,result.size());
        } catch(ArithmeticException|IllegalArgumentException ex) {throw ApiException.unavailable();}
    }
    public LoanCreated one(long userId,long loanId) {
        var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var source=sources.visibleLoanSnapshot(userId,loanId,today);
        if(source.loans().isEmpty())throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
        try{return view(userId,source.loans().getFirst(),source,today).loan();}
        catch(ArithmeticException|IllegalArgumentException e){throw ApiException.unavailable();}
    }

    private View view(long userId,Loan l,Snapshot s,LocalDate today) {
        long savings=LoanSavingsCalculator.balance(l,s);
        var next=next(l,s,today,savings);
        var initialTarget=l.initialPlan().targetPayoffDate();
        var forecast=forecast(userId,l,today,initialTarget);
        long requiredSaving=LoanSavingsCalculator.required(l,savings,today,forecast.targetPayoffDate()!=null?forecast.targetPayoffDate():initialTarget);
        long paid=Math.subtractExact(l.originalPrincipal(),l.storedRemainingPrincipal());
        var progress=BigDecimal.valueOf(paid).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(l.originalPrincipal()),2,RoundingMode.HALF_UP);
        var rate=s.rates().stream().filter(r->r.loanId()==l.loanId())
            .max(Comparator.comparing(Rate::effectiveDate).thenComparingLong(Rate::id)).orElse(null);
        var updated=rate==null?null:rate.createdAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
        return new View(new LoanCreated(l.loanId(),l.productId(),l.institutionName(),l.productName(),l.loanType(),l.loanForm(),l.method(),
            l.originalPrincipal(),l.storedRemainingPrincipal(),l.annualRate(),l.rateType(),l.rateResetMonths(),l.nextRateResetDate(),
            l.startDate(),l.maturityDate(),l.paymentDay(),l.graceMonths(),l.postGraceMethod(),next.contractual(),next.estimate(),
            next.principal(),next.interest(),forecast.remainingInterest(),next.date(),forecast.targetPayoffDate(),forecast.estimatedPayoffDate(),
            paid,progress,l.storedStatus(),today,l.method()==RepaymentMethod.BULLET?"LUMP_SUM":"INSTALLMENT","MANUAL",updated,false,
            l.rateType()==RateType.VARIABLE?"MANUAL_REQUIRED":"NOT_APPLICABLE",l.prepaymentPolicy(),l.originalFeeMethod(),l.feeRate(),
            l.feeStart(),l.feeEnd(),l.feeExempt(),savings,requiredSaving,l.latestSnapshotDate(),l.latestSnapshotPrincipal()),next.required());
    }

    private Forecast forecast(long userId,Loan l,LocalDate today,LocalDate initialTarget) {
        if(l.storedStatus()!=LoanStatus.ACTIVE||l.storedRemainingPrincipal()==0)return new Forecast(null,null,null);
        LocalDate target=initialTarget;
        LocalDate estimated=null;
        Long interest=null;
        try {
            var payoffService=payoffs.getIfAvailable();
            if(payoffService!=null) {
                var result=payoffService.current(userId,l.loanId());
                if(result.plan()!=null)target=result.plan().targetPayoffDate();
                var sim=result.projection()!=null?result.projection().simulation():null;
                if(sim!=null) {
                    estimated=sim.projectedPayoffDate();
                    interest=sim.totalInterest();
                }
            }
            if(target==null) {
                var planService=plans.getIfAvailable();
                if(planService!=null) {
                    var goalDate=l.storedNextDueDate().isAfter(today)?l.storedNextDueDate():today;
                    target=planService.forDate(userId,l.loanId(),goalDate,today).map(SavedPlanSource::targetPayoffDate).orElse(initialTarget);
                }
            }
        } catch(ApiException ignored) {
            // Preserve V2-only compatibility if versioned plan storage is absent in an isolated migration test.
        }
        return new Forecast(target,estimated,interest);
    }

    private Next next(Loan l,Snapshot s,LocalDate today,long savings) {
        if(l.storedStatus()==LoanStatus.PAID)return new Next(null,null,null,null,0,0);
        LocalDate cursor=l.storedNextDueDate();
        boolean foundAnchor=s.dues().stream().anyMatch(d->d.loanId()==l.loanId()&&d.dueDate().equals(l.storedNextDueDate()));
        if(!foundAnchor&&cursor.isBefore(today))throw ApiException.unavailable();
        for(var due:s.dues()) {
            if(due.loanId()!=l.loanId()||due.dueDate().isBefore(cursor))continue;
            long allocated=0;
            for(var a:s.allocations())if(a.dueItemId()==due.id()) {
                var payment=s.payments().stream().filter(p->p.id()==a.paymentId()).findFirst().orElseThrow(ApiException::unavailable);
                if(payment.kind()!=PaymentKind.REGULAR)throw ApiException.unavailable();
                allocated=Math.addExact(allocated,Math.addExact(Math.addExact(a.principal(),a.interest()),a.fee()));
            }
            boolean completed=due.requiredAmount()>0&&allocated>=due.requiredAmount();
            if(completed||due.requiredAmount()==0&&due.dueDate().isBefore(today)) {
                cursor=LoanTermMapper.after(l,due.dueDate());continue;
            }
            // Frozen first-cycle interest survives an actual extra-principal payment during that cycle.
            return new Next(due.dueDate(),due.contractualAmount(),due.estimatedPrincipal(),due.estimatedInterest(),
                Math.addExact(due.estimatedPrincipal(),due.estimatedInterest()),due.requiredAmount());
        }
        if(cursor.isAfter(l.maturityDate()))throw ApiException.unavailable();
        // A bank amount only belongs to its original cycle. A completed/expired cycle cannot lend it to a later one.
        Long contractual=cursor.equals(l.storedNextDueDate())?l.contractualNextAmount():null;
        var terms=LoanTermMapper.current(l,cursor,contractual,savings);
        int horizon=Math.toIntExact(java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(today),YearMonth.from(cursor))+1);
        var rows=engine.simulate(new SimulationInput(List.of(terms),today,Math.max(1,horizon),Map.of(),Map.of(),null,"NONE")).rows();
        if(rows.isEmpty())throw ApiException.unavailable();
        var first=rows.getFirst();long estimate=Math.addExact(first.scheduledPrincipal(),first.interest());
        return new Next(first.date(),contractual,first.scheduledPrincipal(),first.interest(),estimate,contractual==null?estimate:contractual);
    }

    private record Next(LocalDate date,Long contractual,Long principal,Long interest,long estimate,long required) {}
    private record Forecast(LocalDate targetPayoffDate,LocalDate estimatedPayoffDate,Long remainingInterest) {}
    private record View(LoanCreated loan,long required) {}
}
