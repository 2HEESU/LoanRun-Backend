package com.loanrun.loan.service;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.RepaymentEngine;
import com.loanrun.common.error.ApiException;
import com.loanrun.finance.engine.RepaymentDates;
import com.loanrun.loan.domain.LoanSavingsCalculator;
import com.loanrun.loan.dto.*;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.read.LoanSources.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Current materialized loan balances are authoritative; actual payments must not be subtracted twice. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class LoanListService {
    private final LoanSourceRepository sources;
    private final RepaymentEngine engine;
    private final Clock clock;
    public LoanListService(LoanSourceRepository sources,RepaymentEngine engine,Clock clock) {
        this.sources=sources;this.engine=engine;this.clock=clock;
    }

    public LoanList list(long userId,String status) {
        if(!Set.of("ACTIVE","PAID","ALL").contains(status))throw ApiException.validation("status","입력값이 올바르지 않습니다.");
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var snapshot=sources.visibleSnapshot(userId,today,status);
        try {
            var result=new ArrayList<LoanCreated>();long balance=0,due=0;
            for(var loan:snapshot.loans()) {
                var view=view(loan,snapshot,today);result.add(view.loan());
                if(loan.storedStatus()==LoanStatus.ACTIVE) {
                    balance=Math.addExact(balance,loan.storedRemainingPrincipal());
                    due=Math.addExact(due,view.required());
                }
            }
            // V2's immutable registration plan has no current plan/observed-speed projection.
            // Do not replace an unknown plan-based payoff date with contractual maturity.
            return new LoanList(result,balance,due,null,result.size());
        } catch(ArithmeticException|IllegalArgumentException ex) {throw ApiException.unavailable();}
    }

    private View view(Loan l,Snapshot s,LocalDate today) {
        long savings=LoanSavingsCalculator.balance(l,s);
        var next=next(l,s,today,savings);
        long requiredSaving=LoanSavingsCalculator.required(l,savings,today,l.initialPlan().targetPayoffDate());
        long paid=Math.subtractExact(l.originalPrincipal(),l.storedRemainingPrincipal());
        var progress=BigDecimal.valueOf(paid).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(l.originalPrincipal()),2,RoundingMode.HALF_UP);
        var rate=s.rates().stream().filter(r->r.loanId()==l.loanId())
            .max(Comparator.comparing(Rate::effectiveDate).thenComparingLong(Rate::id)).orElse(null);
        var updated=rate==null?null:rate.createdAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
        return new View(new LoanCreated(l.loanId(),l.productId(),l.institutionName(),l.productName(),l.loanType(),l.loanForm(),l.method(),
            l.originalPrincipal(),l.storedRemainingPrincipal(),l.annualRate(),l.rateType(),l.rateResetMonths(),l.nextRateResetDate(),
            l.startDate(),l.maturityDate(),l.paymentDay(),l.graceMonths(),l.postGraceMethod(),next.contractual(),next.estimate(),paid,progress,
            l.storedStatus(),today,l.method()==RepaymentMethod.BULLET?"LUMP_SUM":"INSTALLMENT","MANUAL",updated,false,
            l.rateType()==RateType.VARIABLE?"MANUAL_REQUIRED":"NOT_APPLICABLE",l.prepaymentPolicy(),l.originalFeeMethod(),l.feeRate(),
            l.feeStart(),l.feeEnd(),l.feeExempt(),savings,requiredSaving,l.latestSnapshotDate(),l.latestSnapshotPrincipal()),next.required());
    }

    private Next next(Loan l,Snapshot s,LocalDate today,long savings) {
        if(l.storedStatus()==LoanStatus.PAID)return new Next(l.storedNextDueDate(),null,0,0);
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
                cursor=after(l,due.dueDate());continue;
            }
            // Frozen first-cycle interest survives an actual extra-principal payment during that cycle.
            return new Next(due.dueDate(),due.contractualAmount(),Math.addExact(due.estimatedPrincipal(),due.estimatedInterest()),due.requiredAmount());
        }
        if(cursor.isAfter(l.maturityDate()))throw ApiException.unavailable();
        // A bank amount only belongs to its original cycle. A completed/expired cycle cannot lend it to a later one.
        Long contractual=cursor.equals(l.storedNextDueDate())?l.contractualNextAmount():null;
        var terms=terms(l,cursor,contractual,savings);
        int horizon=Math.toIntExact(java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(today),YearMonth.from(cursor))+1);
        var rows=engine.simulate(new SimulationInput(List.of(terms),today,Math.max(1,horizon),Map.of(),Map.of(),null,"NONE")).rows();
        if(rows.isEmpty())throw ApiException.unavailable();
        var first=rows.getFirst();long estimate=Math.addExact(first.scheduledPrincipal(),first.interest());
        return new Next(first.date(),contractual,estimate,contractual==null?estimate:contractual);
    }

    private static LocalDate after(Loan l,LocalDate date) {
        if(!date.isBefore(l.maturityDate()))return l.maturityDate().plusDays(1);
        var next=RepaymentDates.dueDate(YearMonth.from(date).plusMonths(1),l.paymentDay());
        return YearMonth.from(next).isBefore(YearMonth.from(l.maturityDate()))?next:l.maturityDate();
    }

    private static LoanTerms terms(Loan l,LocalDate next,Long contractual,long savings) {
        return new LoanTerms(l.loanId(),l.userId(),l.productName(),l.loanType(),l.originalPrincipal(),l.storedRemainingPrincipal(),
            l.latestSnapshotDate(),l.annualRate(),l.rateType(),l.method(),l.graceEnd(),l.postGraceMethod(),next,l.paymentDay(),l.maturityDate(),
            contractual,l.baseAnchor(),l.fixedPrincipal(),l.prepaymentPolicy(),Boolean.TRUE.equals(l.feeExempt())?FeeMethod.NONE:l.originalFeeMethod(),
            l.feeRate(),l.feeStart(),l.feeEnd(),l.initialSavings(),savings,l.storedStatus(),l.nextRateResetDate());
    }
    private record Next(LocalDate date,Long contractual,long estimate,long required) {}
    private record View(LoanCreated loan,long required) {}
}
