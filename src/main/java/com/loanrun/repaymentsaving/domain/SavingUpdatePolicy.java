package com.loanrun.repaymentsaving.domain;

import com.loanrun.common.error.ApiException;
import com.loanrun.loan.domain.LoanSavingsCalculator;
import com.loanrun.loan.read.LoanSources.*;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;

/** Does not invent a common intraday order for the two independent V2 ledgers. */
public final class SavingUpdatePolicy {
    private SavingUpdatePolicy() {}
    private static final BigInteger MAX=BigInteger.valueOf(Long.MAX_VALUE);
    private static final class Day {BigInteger additions=BigInteger.ZERO,withdrawals=BigInteger.ZERO;}
    /** Removing a record has the same asset effect as replacing its contribution with zero. */
    public static void verifyRemoval(Loan loan,Snapshot source,Saving original) {
        verify(loan,source,original,original.savedAt(),0);
    }
    public static void verify(Loan loan,Snapshot source,Saving original,LocalDate date,long amount) {
        // Existing malformed sources are unavailable; proposed negative balances/overflow are a conflict.
        LoanSavingsCalculator.balance(loan,source);
        if(original.userId()!=loan.userId()||original.loanId()!=loan.loanId()
            ||source.savings().stream().filter(s->s.id()==original.id()&&s.equals(original)).count()!=1)throw ApiException.unavailable();
        var days=new TreeMap<LocalDate,Day>();
        for(var s:source.savings())if(s.loanId()==loan.loanId()) {
            var day=days.computeIfAbsent(s.savedAt(),d->new Day());day.additions=day.additions.add(BigInteger.valueOf(s.amount()));
        }
        for(var p:source.payments())if(p.loanId()==loan.loanId()) {
            var day=days.computeIfAbsent(p.paidAt(),d->new Day());day.withdrawals=day.withdrawals.add(BigInteger.valueOf(p.savingsUsed()));
        }
        days.computeIfAbsent(date,d->new Day());
        BigInteger oldBalance=BigInteger.valueOf(loan.initialSavings()),balance=oldBalance;
        var oldAmount=BigInteger.valueOf(original.amount());var newAmount=BigInteger.valueOf(amount);
        for(var entry:days.entrySet()) {
            var day=entry.getValue();boolean oldDay=entry.getKey().equals(original.savedAt()),newDay=entry.getKey().equals(date);
            var change=newDay?newAmount:BigInteger.ZERO;if(oldDay)change=change.subtract(oldAmount);
            var minimumDifference=balance.subtract(oldBalance);
            if(oldDay)minimumDifference=minimumDifference.min(balance.subtract(oldBalance).add(change));
            // If a loss is possible at a withdrawal, today's deposits cannot be assumed to precede it.
            if(minimumDifference.signum()<0&&day.withdrawals.signum()>0&&balance.compareTo(day.withdrawals)<0)throw conflict();
            oldBalance=oldBalance.add(day.additions).subtract(day.withdrawals);
            balance=balance.add(day.additions).add(change).subtract(day.withdrawals);
            if(balance.signum()<0||balance.compareTo(MAX)>0)throw conflict();
        }
    }
    public static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"SAVING_CONFLICT","적립 잔액 또는 이미 사용된 금액과 충돌합니다.");}
}
