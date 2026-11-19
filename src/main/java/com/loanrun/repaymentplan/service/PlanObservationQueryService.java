package com.loanrun.repaymentplan.service;

import com.loanrun.common.contract.FinanceModels.RepaymentMethod;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.payment.service.PaymentObservationQueryService;
import com.loanrun.repaymentsaving.service.SavingObservationQueryService;
import com.loanrun.repaymentplan.domain.PlanProjectionBasisPolicy.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.*;

/** Current projection inputs, not historical knowledge reconstruction or a completion writer. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PlanObservationQueryService {
    private final LoanSourceRepository loans;
    private final PaymentObservationQueryService payments;
    private final SavingObservationQueryService savings;
    private final Clock clock;
    public PlanObservationQueryService(LoanSourceRepository loans,PaymentObservationQueryService payments,SavingObservationQueryService savings,Clock clock) {
        this.loans=loans;this.payments=payments;this.savings=savings;this.clock=clock;
    }
    public record Window(long userId,long loanId,LocalDate asOf,Instant knownAt,LocalDate registeredOn,RepaymentMethod method,List<Observation> observations) {
        public Window {observations=List.copyOf(observations);}
    }
    public Window current(long userId,long loanId) {
        try {return readCurrent(userId,loanId);}
        catch(ArithmeticException|IllegalArgumentException|DataAccessException e){throw ApiException.unavailable();}
    }
    private Window readCurrent(long userId,long loanId) {
        var knownAt=clock.instant();var date=knownAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        var loan=loans.loan(userId,loanId);
        if(loan.createdAt().isAfter(knownAt))throw ApiException.unavailable();
        var registered=loan.createdAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        var month=YearMonth.from(date);var first=month.minusMonths(6);var registration=YearMonth.from(registered);
        if(first.isBefore(registration))first=registration;
        var result=new ArrayList<Observation>();
        for(var cursor=first;cursor.isBefore(month);cursor=cursor.plusMonths(1)) {
            if(loan.method()==RepaymentMethod.BULLET) {
                var observed=savings.observeAt(userId,loanId,cursor,knownAt);
                result.add(new Observation(userId,loanId,cursor,Metric.SAVING_ADDITION,observed.observed(),
                    observed.observed()?observed.actuals().additions():null));
            } else {
                var observed=payments.observeAt(userId,cursor,loanId,knownAt);
                result.add(new Observation(userId,loanId,cursor,Metric.EXTRA_PRINCIPAL,observed.observed(),
                    observed.observed()?observed.actuals().extraPrincipal():null));
            }
        }
        return new Window(userId,loanId,date,knownAt,registered,loan.method(),result);
    }
}
