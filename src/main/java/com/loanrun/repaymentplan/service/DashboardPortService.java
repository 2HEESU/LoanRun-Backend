package com.loanrun.repaymentplan.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.contract.DashboardQueryPort.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.PlanPresentationPort.PlanView;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.domain.LoanSavingsCalculator;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.read.LoanSources.*;
import com.loanrun.payment.domain.DueStateCalculator.Status;
import com.loanrun.payment.read.StoredDueProjection;
import com.loanrun.payment.service.StoredDueQueryService;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Owner-scoped Team 1 ledger/plan snapshot for API #42 without risk or report orchestration. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class DashboardPortService implements DashboardQueryPort {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private final LoanSourceRepository sources;
    private final StoredDueQueryService dues;
    private final PlanSourceQueryService planSources;
    private final PlanPayoffQueryService payoffs;
    private final PlanPresentationPort presentations;
    private final ObjectProvider<BudgetQueryPort> budgets;
    private final Clock clock;

    public DashboardPortService(LoanSourceRepository sources,StoredDueQueryService dues,
            PlanSourceQueryService planSources,PlanPayoffQueryService payoffs,
            PlanPresentationPort presentations,ObjectProvider<BudgetQueryPort> budgets,Clock clock) {
        this.sources=sources;this.dues=dues;this.planSources=planSources;this.payoffs=payoffs;
        this.presentations=presentations;this.budgets=budgets;this.clock=clock;
    }

    @Override
    public Snapshot snapshot(long userId,Long selectedLoanId,LocalDate asOf) {
        if(userId<=0)throw new IllegalArgumentException("Positive internal owner required");
        Objects.requireNonNull(asOf);
        if(selectedLoanId!=null&&selectedLoanId<=0)throw missing();
        try {
            var today=LocalDate.now(clock.withZone(KST));
            if(!asOf.equals(today))throw ApiException.unavailable();
            var visible=sources.visibleSnapshot(userId,today,"ALL");
            Loan selected=null;
            if(selectedLoanId!=null) {
                selected=visible.loans().stream().filter(l->l.loanId()==selectedLoanId).findFirst().orElseThrow(DashboardPortService::missing);
            }
            if(visible.loans().isEmpty()) {
                return new Snapshot(List.of(),0,null,null,null,new MonthlySummary(0,0,0,0,0,0,0,0,0,0),List.of());
            }

            Map<Long,Loan> loanById=new LinkedHashMap<>();
            List<LoanProgress> loanList=new ArrayList<>(visible.loans().size());
            for(var loan:visible.loans()) {
                loanById.put(loan.loanId(),loan);
                long savings=LoanSavingsCalculator.balance(loan,visible);
                long paid=Math.subtractExact(loan.originalPrincipal(),loan.storedRemainingPrincipal());
                var pct=progress(paid,loan.originalPrincipal());
                long requiredSaving=requiredMonthlySaving(userId,loan,savings,today);
                loanList.add(new LoanProgress(loan.loanId(),loan.productName(),loan.storedRemainingPrincipal(),
                    paid,pct,savings,loan.storedStatus().name(),requiredSaving));
            }

            List<Loan> scopeLoans=selected!=null?List.of(selected):visible.loans();
            List<Loan> activeScope=scopeLoans.stream().filter(l->l.storedStatus()==LoanStatus.ACTIVE).toList();
            long totalRemaining=0;
            for(var loan:scopeLoans)totalRemaining=Math.addExact(totalRemaining,loan.storedRemainingPrincipal());

            BigDecimal overallProgress=overallProgress(selected,scopeLoans,activeScope);
            LocalDate estimatedPayoff=estimatedPayoffDate(userId,activeScope);

            var currentMonth=YearMonth.from(today);
            var monthDues=dues.range(userId,new DateRange(currentMonth.atDay(1),currentMonth.atEndOfMonth()),selectedLoanId).stream()
                .filter(r->r.source().loanStatus()!=LoanStatus.DELETED&&loanById.containsKey(r.source().due().loanId())).toList();
            for(var row:monthDues)if(!row.evaluatedOn().equals(today))throw ApiException.unavailable();
            for(var loan:activeScope) {
                if(!currentMonth.isBefore(YearMonth.from(loan.initialPlan().effectiveDate()))
                    &&!currentMonth.isAfter(YearMonth.from(loan.maturityDate()))
                    &&monthDues.stream().noneMatch(r->r.source().due().loanId()==loan.loanId())) {
                    throw ApiException.unavailable();
                }
            }

            Map<YearMonth,MonthPlanContext> planCache=new HashMap<>();
            var currentContext=monthContext(userId,today,currentMonth,planCache);

            List<DueItem> checklist=new ArrayList<>(monthDues.size());
            long scheduled=0,remainingRequired=0,plannedExtraTotal=0,plannedSavingTotal=0;
            int completedCount=0;
            Set<Long> seenLoans=new HashSet<>();
            for(var row:monthDues) {
                var loan=loanById.get(row.source().due().loanId());
                var item=dueItem(row,loan,visible,currentMonth,currentContext);
                checklist.add(item);
                scheduled=Math.addExact(scheduled,item.requiredAmount());
                remainingRequired=Math.addExact(remainingRequired,item.outstandingAmount());
                if(row.state().status()==Status.PAID)completedCount++;
                if(seenLoans.add(loan.loanId())) {
                    plannedExtraTotal=Math.addExact(plannedExtraTotal,item.plannedExtraAmount());
                    plannedSavingTotal=Math.addExact(plannedSavingTotal,item.plannedSavingAmount());
                }
            }
            checklist.sort(Comparator.comparing(DueItem::dueDate).thenComparingLong(DueItem::dueItemId));

            Set<Long> scopeIds=new HashSet<>();
            for(var loan:scopeLoans)scopeIds.add(loan.loanId());
            long actualRepayment=0,actualExtra=0,actualSaving=0;
            for(var payment:visible.payments()) {
                if(scopeIds.contains(payment.loanId())&&YearMonth.from(payment.paidAt()).equals(currentMonth)) {
                    actualRepayment=Math.addExact(actualRepayment,Math.subtractExact(payment.amount(),payment.savingsUsed()));
                    actualExtra=Math.addExact(actualExtra,payment.extraPrincipal());
                }
            }
            for(var saving:visible.savings()) {
                if(scopeIds.contains(saving.loanId())&&YearMonth.from(saving.savedAt()).equals(currentMonth)) {
                    actualSaving=Math.addExact(actualSaving,saving.amount());
                }
            }

            var summary=new MonthlySummary(scheduled,actualRepayment,remainingRequired,plannedExtraTotal,actualExtra,
                completedCount,checklist.size(),remainingRequired,plannedSavingTotal,actualSaving);

            DueItem nextPayment=findNextPayment(userId,selectedLoanId,today,activeScope,loanById,visible,currentMonth,checklist,planCache);
            return new Snapshot(loanList,totalRemaining,overallProgress,estimatedPayoff,nextPayment,summary,checklist);
        } catch(ArithmeticException|IllegalArgumentException|DataAccessException e) {
            throw ApiException.unavailable();
        }
    }

    private long requiredMonthlySaving(long userId,Loan loan,long savingsBalance,LocalDate today) {
        if(loan.storedStatus()!=LoanStatus.ACTIVE||loan.method()!=RepaymentMethod.BULLET)return 0L;
        var goalDate=loan.storedNextDueDate().isAfter(today)?loan.storedNextDueDate():today;
        var targetDate=planSources.forDate(userId,loan.loanId(),goalDate,today)
            .map(p->p.targetPayoffDate())
            .orElse(loan.initialPlan().targetPayoffDate());
        return LoanSavingsCalculator.required(loan,savingsBalance,today,targetDate);
    }

    private static BigDecimal overallProgress(Loan selected,List<Loan> scopeLoans,List<Loan> activeScope) {
        if(selected!=null) {
            return progress(Math.subtractExact(selected.originalPrincipal(),selected.storedRemainingPrincipal()),selected.originalPrincipal());
        }
        List<Loan> basis=activeScope.isEmpty()?scopeLoans:activeScope;
        long totalOriginal=0,totalPaid=0;
        for(var loan:basis) {
            totalOriginal=Math.addExact(totalOriginal,loan.originalPrincipal());
            totalPaid=Math.addExact(totalPaid,Math.subtractExact(loan.originalPrincipal(),loan.storedRemainingPrincipal()));
        }
        return progress(totalPaid,totalOriginal);
    }

    private LocalDate estimatedPayoffDate(long userId,List<Loan> activeScope) {
        if(activeScope.isEmpty())return null;
        LocalDate latest=null;
        for(var loan:activeScope) {
            var result=payoffs.current(userId,loan.loanId());
            if(result.projection()==null||result.projection().simulation()==null
                ||result.projection().simulation().projectedPayoffDate()==null)return null;
            var projected=result.projection().simulation().projectedPayoffDate();
            if(latest==null||projected.isAfter(latest))latest=projected;
        }
        return latest;
    }

    private DueItem findNextPayment(long userId,Long selectedLoanId,LocalDate today,List<Loan> activeScope,
            Map<Long,Loan> loanById,com.loanrun.loan.read.LoanSources.Snapshot visible,
            YearMonth currentMonth,List<DueItem> checklist,Map<YearMonth,MonthPlanContext> planCache) {
        if(activeScope.isEmpty())return null;
        var rows=dues.range(userId,new DateRange(LocalDate.of(1,1,1),LocalDate.of(9999,12,31)),selectedLoanId);
        for(var row:rows) {
            if(!row.evaluatedOn().equals(today))throw ApiException.unavailable();
            if(row.source().loanStatus()!=LoanStatus.ACTIVE)continue;
            if(row.state().outstandingAmount()==0)continue;
            var due=row.source().due();
            var loan=loanById.get(due.loanId());
            if(loan==null)continue;
            var month=YearMonth.from(due.dueDate());
            if(month.equals(currentMonth)) {
                for(var item:checklist)if(item.dueItemId()==due.id())return item;
            }
            var ctx=monthContext(userId,today,month,planCache);
            return dueItem(row,loan,visible,month,ctx);
        }
        return null;
    }

    private MonthPlanContext monthContext(long userId,LocalDate today,YearMonth month,Map<YearMonth,MonthPlanContext> cache) {
        var existing=cache.get(month);
        if(existing!=null)return existing;
        var budget=budgets.getIfAvailable();
        if(budget==null)throw ApiException.unavailable();
        var overrides=budget.monthlyOverrides(userId,month);
        if(overrides==null)throw ApiException.unavailable();
        var views=presentations.monthViews(userId,today,month,overrides);
        if(views==null)throw ApiException.unavailable();
        Map<Long,PlanView> viewMap=new HashMap<>();
        for(var view:views) {
            if(view==null||viewMap.put(view.loanId(),view)!=null)throw ApiException.unavailable();
        }
        Map<Long,PlannedAmount> overrideMap=new HashMap<>();
        for(var override:overrides) {
            if(override==null||overrideMap.put(override.loanId(),override)!=null)throw ApiException.unavailable();
        }
        var ctx=new MonthPlanContext(viewMap,overrideMap);
        cache.put(month,ctx);
        return ctx;
    }

    private static DueItem dueItem(StoredDueProjection row,Loan loan,com.loanrun.loan.read.LoanSources.Snapshot visible,
            YearMonth month,MonthPlanContext context) {
        var due=row.source().due();
        var state=row.state();
        var plan=context.views().get(loan.loanId());
        var override=context.overrides().get(loan.loanId());
        boolean bullet=loan.method()==RepaymentMethod.BULLET;

        long plannedExtra;
        long plannedSaving;
        if(bullet) {
            plannedExtra=0L;
            if(plan!=null&&plan.plannedSavingAmount()!=null)plannedSaving=plan.plannedSavingAmount();
            else if(override!=null&&override.allocationType()==AllocationType.REPAYMENT_SAVING)plannedSaving=override.cashAmount();
            else if(plan==null)plannedSaving=0L;
            else throw ApiException.unavailable();
        } else {
            plannedSaving=0L;
            if(plan!=null&&plan.plannedExtraAmount()!=null)plannedExtra=plan.plannedExtraAmount();
            else if(override!=null&&override.allocationType()==AllocationType.EXTRA_PAYMENT)plannedExtra=override.cashAmount();
            else if(plan!=null&&plan.extraMonthlyPayment()!=null)plannedExtra=plan.effectiveDate().isAfter(month.atEndOfMonth())?0L:plan.extraMonthlyPayment();
            else if(plan==null)plannedExtra=0L;
            else throw ApiException.unavailable();
        }

        long paidExtra=0,actualSaving=0;
        for(var payment:visible.payments()) {
            if(payment.loanId()==loan.loanId()&&YearMonth.from(payment.paidAt()).equals(month)) {
                paidExtra=Math.addExact(paidExtra,payment.extraPrincipal());
            }
        }
        for(var saving:visible.savings()) {
            if(saving.loanId()==loan.loanId()&&YearMonth.from(saving.savedAt()).equals(month)) {
                actualSaving=Math.addExact(actualSaving,saving.amount());
            }
        }
        String status=state.status().name();
        long totalPlanned=Math.addExact(due.requiredAmount(),plannedExtra);
        long remainingExtra=Math.max(0L,Math.subtractExact(plannedExtra,paidExtra));
        return new DueItem(due.id(),due.loanId(),due.dueDate(),due.requiredAmount(),plannedExtra,
            state.paidRequiredAmount(),paidExtra,state.outstandingAmount(),status,loan.productName(),
            totalPlanned,status,remainingExtra,due.confirmedUnpaid(),plannedSaving,actualSaving);
    }

    private static BigDecimal progress(long paid,long original) {
        if(original<=0)return null;
        return BigDecimal.valueOf(paid).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(original),2,RoundingMode.HALF_UP);
    }

    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");}
    private record MonthPlanContext(Map<Long,PlanView> views,Map<Long,PlannedAmount> overrides) {}
}
