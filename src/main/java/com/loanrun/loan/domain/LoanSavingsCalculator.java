package com.loanrun.loan.domain;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.finance.engine.RepaymentDates;
import com.loanrun.finance.engine.RepaymentMath;
import com.loanrun.loan.read.LoanSources.*;
import java.time.LocalDate;
import java.util.TreeMap;

/** Shared current asset math. Date totals do not assert an intraday order across two independent ledgers. */
public final class LoanSavingsCalculator {
    private LoanSavingsCalculator() {}
    public static long balance(Loan loan,Snapshot source) {
        var changes=new TreeMap<LocalDate,Long>();
        for(var saving:source.savings())if(saving.loanId()==loan.loanId())
            changes.merge(saving.savedAt(),saving.amount(),Math::addExact);
        for(var payment:source.payments())if(payment.loanId()==loan.loanId())
            changes.merge(payment.paidAt(),-payment.savingsUsed(),Math::addExact);
        long balance=loan.initialSavings();
        for(long change:changes.values()) {
            balance=Math.addExact(balance,change);
            if(balance<0)throw ApiException.unavailable();
        }
        if(loan.method()!=RepaymentMethod.BULLET&&balance!=0)throw ApiException.unavailable();
        return balance;
    }

    public static long required(Loan l,long savings,LocalDate today,LocalDate target) {
        if(l.storedStatus()!=LoanStatus.ACTIVE||l.method()!=RepaymentMethod.BULLET)return 0;
        if(target==null||target.isAfter(l.maturityDate()))throw ApiException.unavailable();
        // Saving dates are independent of whether this month's regular interest has already been paid.
        var terms=new LoanTerms(l.loanId(),l.userId(),l.productName(),l.loanType(),l.originalPrincipal(),l.storedRemainingPrincipal(),
            l.latestSnapshotDate(),l.annualRate(),l.rateType(),l.method(),l.graceEnd(),l.postGraceMethod(),l.storedNextDueDate(),l.paymentDay(),
            l.maturityDate(),null,l.baseAnchor(),l.fixedPrincipal(),l.prepaymentPolicy(),l.originalFeeMethod(),l.feeRate(),l.feeStart(),l.feeEnd(),
            l.initialSavings(),savings,l.storedStatus(),l.nextRateResetDate());
        long periods=RepaymentDates.remaining(terms,today).stream().filter(d->!d.isAfter(target)).count();
        return RepaymentMath.requiredSaving(l.storedRemainingPrincipal(),savings,Math.toIntExact(periods));
    }
}
