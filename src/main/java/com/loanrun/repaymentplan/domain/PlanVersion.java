package com.loanrun.repaymentplan.domain;

import com.loanrun.common.contract.FinanceModels.*;
import java.time.*;
import java.util.Objects;

/** Confirmed source goals and contract at plan start; no computed performance or observed-month defaults. */
public record PlanVersion(long id,long userId,long loanId,long version,Draft draft,Instant createdAt) {
    public enum Mode { MATURITY,PREFERENCE,TARGET_DATE,EXTRA_AMOUNT,MONTHLY_AMOUNT,STRATEGY }
    public enum Preference { FAST,BALANCED,STABLE }
    public record Inputs(Long extraMonthlyPayment,Long monthlyAmount,Long plannedSavingAmount) {
        public Inputs {
            if(extraMonthlyPayment!=null&&extraMonthlyPayment<0||monthlyAmount!=null&&monthlyAmount<0||plannedSavingAmount!=null&&plannedSavingAmount<0)
                throw new IllegalArgumentException("Negative requested goal");
        }
    }
    public record StrategySource(long reportId,String version,String code) {
        public StrategySource {
            if(reportId<=0||version==null||version.isBlank()||code==null||code.isBlank())throw new IllegalArgumentException("Invalid strategy source");
        }
    }
    public record Draft(long loanId,Mode mode,Preference preference,LocalDate targetDate,LocalDate targetPayoffDate,
        long monthlyExtraCash,long monthlySaving,LocalDate effectiveDate,long inputRevision,LoanTerms termsAtStart,Inputs inputs,StrategySource strategy) {
        public Draft {
            Objects.requireNonNull(mode);Objects.requireNonNull(effectiveDate);Objects.requireNonNull(termsAtStart);Objects.requireNonNull(inputs);
            var t=termsAtStart;
            if(loanId<=0||t.loanId()!=loanId||t.userId()<=0||t.status()!=LoanStatus.ACTIVE||t.method()==null
                ||monthlyExtraCash<0||monthlySaving<0||inputRevision<0||t.principalOriginal()<=0||t.outstanding()<0||t.outstanding()>t.principalOriginal()
                ||t.snapshotDate()==null||t.maturityDate()==null||effectiveDate.isBefore(t.snapshotDate())||effectiveDate.isAfter(t.maturityDate())
                ||(mode==Mode.PREFERENCE)!=(preference!=null)||(mode==Mode.STRATEGY)!=(strategy!=null)
                ||mode==Mode.TARGET_DATE&&targetDate==null
                ||mode==Mode.EXTRA_AMOUNT&&inputs.extraMonthlyPayment()==null||mode==Mode.MONTHLY_AMOUNT&&inputs.monthlyAmount()==null
                ||targetDate!=null&&(targetDate.isBefore(effectiveDate)||targetDate.isAfter(t.maturityDate()))
                ||targetPayoffDate!=null&&(targetPayoffDate.isBefore(effectiveDate)||targetPayoffDate.isAfter(t.maturityDate()))
                ||t.method()==RepaymentMethod.BULLET&&monthlyExtraCash!=0||t.method()!=RepaymentMethod.BULLET&&monthlySaving!=0)
                throw new IllegalArgumentException("Invalid plan source");
        }
    }
    /** The future API coordinator maps stale sources to its documented preview/conflict response. */
    public static final class Conflict extends RuntimeException {
        public Conflict(){super("Plan source version or input revision changed");}
    }
}
