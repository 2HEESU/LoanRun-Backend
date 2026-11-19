package com.loanrun.loan.domain;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.finance.engine.RepaymentDates;
import com.loanrun.loan.read.LoanSources.Loan;
import java.time.*;

/** Mapping of current materialized facts, never a historical balance reconstruction. */
public final class LoanTermMapper {
    private LoanTermMapper() {}
    public static LoanTerms current(Loan l,LocalDate next,Long contractual,long savings) {
        return new LoanTerms(l.loanId(),l.userId(),l.productName(),l.loanType(),l.originalPrincipal(),l.storedRemainingPrincipal(),
            l.latestSnapshotDate(),l.annualRate(),l.rateType(),l.method(),l.graceEnd(),l.postGraceMethod(),next,l.paymentDay(),l.maturityDate(),
            contractual,l.baseAnchor(),l.fixedPrincipal(),l.prepaymentPolicy(),Boolean.TRUE.equals(l.feeExempt())?FeeMethod.NONE:l.originalFeeMethod(),
            l.feeRate(),l.feeStart(),l.feeEnd(),l.initialSavings(),savings,l.storedStatus(),l.nextRateResetDate());
    }
    public static LocalDate after(Loan l,LocalDate date) {
        if(!date.isBefore(l.maturityDate()))return l.maturityDate().plusDays(1);
        var next=RepaymentDates.dueDate(YearMonth.from(date).plusMonths(1),l.paymentDay());
        return YearMonth.from(next).isBefore(YearMonth.from(l.maturityDate()))?next:l.maturityDate();
    }
}
