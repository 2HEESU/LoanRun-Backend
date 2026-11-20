package com.loanrun.loan.service;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.RepaymentEngine;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.domain.*;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.read.LoanSources.*;
import com.loanrun.payment.domain.DueStateCalculator;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Current-only calculation basis. Unsupported frozen obligations remain unknown rather than being replayed as paid. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class CurrentLoanTermsQueryService {
    private final LoanSourceRepository sources;
    private final RepaymentEngine engine;
    private final Clock clock;
    public CurrentLoanTermsQueryService(LoanSourceRepository sources,RepaymentEngine engine,Clock clock){this.sources=sources;this.engine=engine;this.clock=clock;}
    public enum Reason { AVAILABLE,INACTIVE_OR_ZERO,PAST_DUE_UNSETTLED,PARTIALLY_SETTLED_DUE,UNSUPPORTED_FROZEN_DUE,NO_REMAINING_CYCLE }
    public record Current(LocalDate asOf,Loan loan,LoanTerms terms,Reason reason) {}

    public Current current(long userId,long loanId) {
        if(loanId<=0)throw missing();
        var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        try {
            var source=sources.loanSnapshot(userId,loanId,today);
            if(source.loans().isEmpty())throw missing();
            var loan=source.loans().getFirst();
            if(loan.storedStatus()!=LoanStatus.ACTIVE||loan.storedRemainingPrincipal()==0)return unavailable(today,loan,Reason.INACTIVE_OR_ZERO);
            long savings=LoanSavingsCalculator.balance(loan,source);var cursor=loan.storedNextDueDate();
            if(cursor.isBefore(today)&&source.dues().stream().noneMatch(d->d.dueDate().equals(loan.storedNextDueDate())))
                return unavailable(today,loan,Reason.PAST_DUE_UNSETTLED);
            for(var due:source.dues()) {
                if(due.dueDate().isBefore(cursor))continue;
                if(due.dueDate().isAfter(cursor))break;
                long principal=0,interest=0,fee=0,count=0;
                for(var a:source.allocations())if(a.dueItemId()==due.id()) {
                    var payment=source.payments().stream().filter(p->p.id()==a.paymentId()).findFirst().orElseThrow(ApiException::unavailable);
                    if(payment.kind()!=PaymentKind.REGULAR)throw ApiException.unavailable();
                    principal=Math.addExact(principal,a.principal());interest=Math.addExact(interest,a.interest());fee=Math.addExact(fee,a.fee());count++;
                }
                var state=DueStateCalculator.calculate(due.requiredAmount(),principal,interest,fee,count,due.confirmedUnpaid(),due.dueDate(),today);
                if(due.requiredAmount()>0&&state.outstandingAmount()==0||due.requiredAmount()==0&&due.dueDate().isBefore(today)) {
                    cursor=LoanTermMapper.after(loan,due.dueDate());continue;
                }
                if(due.dueDate().isBefore(today))return unavailable(today,loan,Reason.PAST_DUE_UNSETTLED);
                if(count>0)return unavailable(today,loan,Reason.PARTIALLY_SETTLED_DUE);
                if(due.fee()!=0)return unavailable(today,loan,Reason.UNSUPPORTED_FROZEN_DUE);
                if(source.dues().stream().anyMatch(d->d.dueDate().isAfter(due.dueDate())))
                    return unavailable(today,loan,Reason.UNSUPPORTED_FROZEN_DUE);
                var terms=LoanTermMapper.current(loan,due.dueDate(),due.contractualAmount(),savings);
                int months=Math.toIntExact(ChronoUnit.MONTHS.between(YearMonth.from(today),YearMonth.from(due.dueDate()))+1);
                var comparison=engine.simulate(new SimulationInput(List.of(terms),today,months,Map.of(),Map.of(),null,"NONE"));
                if(comparison.rows().isEmpty()||comparison.insufficient())return unavailable(today,loan,Reason.UNSUPPORTED_FROZEN_DUE);
                var first=comparison.rows().getFirst();
                if(first.scheduledPrincipal()!=due.estimatedPrincipal()||first.interest()!=due.estimatedInterest())
                    return unavailable(today,loan,Reason.UNSUPPORTED_FROZEN_DUE);
                return new Current(today,loan,terms,Reason.AVAILABLE);
            }
            if(cursor.isBefore(today))return unavailable(today,loan,Reason.PAST_DUE_UNSETTLED);
            if(cursor.isAfter(loan.maturityDate()))return unavailable(today,loan,Reason.NO_REMAINING_CYCLE);
            for(var due:source.dues())if(!due.dueDate().isBefore(cursor))return unavailable(today,loan,Reason.UNSUPPORTED_FROZEN_DUE);
            return new Current(today,loan,LoanTermMapper.current(loan,cursor,cursor.equals(loan.storedNextDueDate())?loan.contractualNextAmount():null,savings),Reason.AVAILABLE);
        } catch(ArithmeticException|IllegalArgumentException|DataAccessException e){throw ApiException.unavailable();}
    }
    private static Current unavailable(LocalDate date,Loan loan,Reason reason){return new Current(date,loan,null,reason);}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");}
}
