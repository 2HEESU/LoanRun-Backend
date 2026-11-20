package com.loanrun.repaymentplan.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.read.LoanSources.Loan;
import com.loanrun.payment.domain.DueStateCalculator.Status;
import com.loanrun.payment.service.StoredDueQueryService;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Historical 3m adherence, consecutive extra-goal streak, and 6m confirmed missed-payment count. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PlanPerformancePortService implements PlanPerformanceQueryPort {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private static final int MAX_STREAK_MONTHS=1200;
    private final LoanSourceRepository loans;
    private final PlanPresentationPort presentations;
    private final ObjectProvider<BudgetQueryPort> budgets;
    private final StoredDueQueryService dues;
    private final Clock clock;

    public PlanPerformancePortService(LoanSourceRepository loans,PlanPresentationPort presentations,
            ObjectProvider<BudgetQueryPort> budgets,StoredDueQueryService dues,Clock clock) {
        this.loans=loans;this.presentations=presentations;this.budgets=budgets;this.dues=dues;this.clock=clock;
    }

    @Override
    public Performance performance(long userId,LocalDate asOf) {
        if(userId<=0)throw new IllegalArgumentException("Positive internal owner required");
        Objects.requireNonNull(asOf);
        try {
            var now=clock.instant();var today=now.atZone(KST).toLocalDate();
            if(asOf.isAfter(today))throw ApiException.unavailable();
            var budget=budgets.getIfAvailable();
            if(budget==null)throw ApiException.unavailable();
            var cutoff=asOf.equals(today)?now:asOf.plusDays(1).atStartOfDay(KST).toInstant().minusNanos(1);
            var currentMonth=YearMonth.from(asOf);
            var lastCompleted=currentMonth.minusMonths(1);
            var snapshot=loans.snapshot(userId,lastCompleted.atEndOfMonth());
            if(snapshot.loans().isEmpty())return new Performance(null,null,null);

            var earliestMonth=snapshot.loans().stream()
                .map(l->YearMonth.from(l.createdAt().atZone(KST)))
                .min(Comparator.naturalOrder()).orElse(lastCompleted);
            Map<YearMonth,MonthEvaluation> cache=new HashMap<>();

            var adherence=adherenceRate3m(userId,asOf,lastCompleted,earliestMonth,budget,cache);
            var streak=consecutiveOnTrack(userId,asOf,lastCompleted,earliestMonth,budget,cache);
            var missed=missedPaymentCount6m(userId,asOf,today,cutoff,lastCompleted,snapshot.loans());
            return new Performance(adherence,streak,missed);
        } catch(ArithmeticException|IllegalArgumentException|DataAccessException e) {
            throw ApiException.unavailable();
        }
    }

    private BigDecimal adherenceRate3m(long userId,LocalDate asOf,YearMonth lastCompleted,YearMonth earliestMonth,
            BudgetQueryPort budget,Map<YearMonth,MonthEvaluation> cache) {
        long achieved=0,target=0;
        int observedMonths=0;
        for(int offset=2;offset>=0;offset--) {
            var month=lastCompleted.minusMonths(offset);
            if(month.isBefore(earliestMonth))return null;
            var eval=evaluateMonth(userId,asOf,month,budget,cache);
            if(!eval.observed())return null;
            observedMonths++;
            achieved=Math.addExact(achieved,eval.cappedAchieved());
            target=Math.addExact(target,eval.totalTarget());
        }
        if(observedMonths<3||target<=0)return null;
        return BigDecimal.valueOf(achieved).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(target),2,RoundingMode.HALF_UP);
    }

    private Integer consecutiveOnTrack(long userId,LocalDate asOf,YearMonth lastCompleted,YearMonth earliestMonth,
            BudgetQueryPort budget,Map<YearMonth,MonthEvaluation> cache) {
        int streak=0;
        boolean confirmedAny=false;
        for(int offset=0;offset<MAX_STREAK_MONTHS;offset++) {
            var month=lastCompleted.minusMonths(offset);
            if(month.isBefore(earliestMonth))break;
            var eval=evaluateMonth(userId,asOf,month,budget,cache);
            if(!eval.observed()||eval.totalTarget()<=0)break;
            confirmedAny=true;
            if(!eval.allTargetsMet())break;
            streak++;
        }
        return confirmedAny?streak:null;
    }

    private Integer missedPaymentCount6m(long userId,LocalDate asOf,LocalDate today,Instant cutoff,
            YearMonth lastCompleted,List<Loan> historicalLoans) {
        if(!asOf.equals(today)) {
            var currentSnapshot=loans.snapshot(userId,today);
            if(currentSnapshot.loans().stream().anyMatch(l->l.updatedAt().isAfter(cutoff)))return null;
            if(currentSnapshot.payments().stream().anyMatch(p->p.paidAt().isAfter(asOf)))return null;
        }
        var from=lastCompleted.minusMonths(5).atDay(1);
        var to=lastCompleted.atEndOfMonth();
        var rows=dues.range(userId,new DateRange(from,to),null);
        if(rows.isEmpty())return null;
        Map<Long,Loan> byId=new HashMap<>();
        for(var loan:historicalLoans)byId.put(loan.loanId(),loan);

        int unpaid=0,confirmed=0;
        for(var row:rows) {
            if(!row.evaluatedOn().equals(today))throw ApiException.unavailable();
            var due=row.source().due();
            var loan=byId.get(due.loanId());
            if(loan!=null&&loan.storedStatus()==LoanStatus.DELETED
                &&loan.updatedAt().isBefore(due.dueDate().atStartOfDay(KST).toInstant()))continue;
            var status=row.state().status();
            if(status==Status.UNPAID){unpaid++;confirmed++;}
            else if(status==Status.PAID||status==Status.PARTIAL){confirmed++;}
        }
        return confirmed>0?unpaid:null;
    }

    private MonthEvaluation evaluateMonth(long userId,LocalDate asOf,YearMonth month,BudgetQueryPort budget,
            Map<YearMonth,MonthEvaluation> cache) {
        var existing=cache.get(month);
        if(existing!=null)return existing;
        var overrides=budget.monthlyOverrides(userId,month);
        if(overrides==null)throw ApiException.unavailable();
        var views=presentations.monthViews(userId,asOf,month,overrides);
        if(views==null)throw ApiException.unavailable();
        if(views.isEmpty()) {
            var empty=new MonthEvaluation(false,0,0,false);
            cache.put(month,empty);
            return empty;
        }
        long totalTarget=0,cappedAchieved=0;
        boolean allTargetsMet=true;
        for(var view:views) {
            if(view==null)throw ApiException.unavailable();
            boolean bullet="LUMP_SUM".equals(view.repaymentCategory());
            Long target=bullet?view.plannedSavingAmount():view.plannedExtraAmount();
            Long actual=bullet?view.actualSavingAmount():view.actualExtraAmount();
            if(target==null||actual==null) {
                var unknown=new MonthEvaluation(false,0,0,false);
                cache.put(month,unknown);
                return unknown;
            }
            if(target<0||actual<0)throw ApiException.unavailable();
            if(target>0) {
                totalTarget=Math.addExact(totalTarget,target);
                cappedAchieved=Math.addExact(cappedAchieved,Math.min(actual,target));
                if(actual<target)allTargetsMet=false;
            }
        }
        var result=new MonthEvaluation(true,totalTarget,cappedAchieved,allTargetsMet);
        cache.put(month,result);
        return result;
    }

    private record MonthEvaluation(boolean observed,long totalTarget,long cappedAchieved,boolean allTargetsMet) {}
}
