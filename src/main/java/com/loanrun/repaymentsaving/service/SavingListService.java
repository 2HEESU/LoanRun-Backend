package com.loanrun.repaymentsaving.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.domain.LoanSavingsCalculator;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.repaymentsaving.dto.SavingList;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.util.MultiValueMap;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class SavingListService {
    private final LoanSourceRepository sources;
    private final ObjectProvider<PlanQueryPort> plans;
    private final ObjectProvider<BudgetQueryPort> budgets;
    private final Clock clock;
    public SavingListService(LoanSourceRepository sources,ObjectProvider<PlanQueryPort> plans,ObjectProvider<BudgetQueryPort> budgets,Clock clock) {
        this.sources=sources;this.plans=plans;this.budgets=budgets;this.clock=clock;
    }
    public SavingList list(long userId,MultiValueMap<String,String> query) {
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));long id;
        LocalDate from,to;
        try {
            if(query.keySet().stream().anyMatch(k->!Set.of("loan_id","from","to").contains(k))||query.values().stream().anyMatch(v->v.size()!=1))throw invalid();
            var value=query.getFirst("loan_id");if(value==null||!value.matches("[0-9]+"))throw invalid();
            id=Long.parseLong(value);if(id<=0)throw invalid();
            from=date(query.getFirst("from"),LocalDate.of(1,1,1));to=date(query.getFirst("to"),today);
            if(to.isBefore(from))throw invalid();
        } catch(NumberFormatException|DateTimeParseException ex) {throw invalid();}
        var snapshot=sources.loanSnapshot(userId,id,today);
        if(snapshot.loans().isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"SAVING_NOT_FOUND","리소스를 찾을 수 없습니다.");
        var loan=snapshot.loans().getFirst();if(loan.method()!=RepaymentMethod.BULLET)throw invalid();
        try {
            long balance=LoanSavingsCalculator.balance(loan,snapshot);
            PlanSnapshot plan=loan.initialPlan();var provider=plans.getIfAvailable();
            boolean registrationOnly=loan.storedStatus()==LoanStatus.ACTIVE&&provider instanceof com.loanrun.repaymentplan.read.RegistrationPlanFallback fallback&&fallback.registrationFallbackAllowed();
            if(provider!=null&&!registrationOnly&&loan.storedStatus()==LoanStatus.ACTIVE) {
                var current=provider instanceof com.loanrun.repaymentplan.read.RegistrationPlanFallback scoped
                    ?java.util.List.of(scoped.currentPlan(userId,id,today)):provider.currentPlans(userId,today);if(current==null)throw ApiException.unavailable();
                var matches=current.stream().filter(p->p.loanId()==id).toList();
                if(matches.size()!=1)throw ApiException.unavailable();plan=matches.getFirst();
            }
            if(plan.loanId()!=id||plan.monthlySaving()<0||plan.effectiveDate()==null)throw ApiException.unavailable();
            long planned=loan.storedStatus()==LoanStatus.ACTIVE&&!YearMonth.from(plan.effectiveDate()).isAfter(YearMonth.from(today))?plan.monthlySaving():0;
            var budget=budgets.getIfAvailable();
            if(budget!=null&&loan.storedStatus()==LoanStatus.ACTIVE) {
                var overrides=budget.monthlyOverrides(userId,YearMonth.from(today));if(overrides==null)throw ApiException.unavailable();
                var matches=overrides.stream().filter(a->a.loanId()==id).toList();
                if(matches.size()>1)throw ApiException.unavailable();
                if(!matches.isEmpty()) {
                    var override=matches.getFirst();
                    if(override.allocationType()!=AllocationType.REPAYMENT_SAVING||override.cashAmount()<0)throw ApiException.unavailable();
                    planned=override.cashAmount();
                }
            }
            long required=LoanSavingsCalculator.required(loan,balance,today,plan.targetPayoffDate());
            long actual=0;
            for(var saving:snapshot.savings())if(YearMonth.from(saving.savedAt()).equals(YearMonth.from(today)))actual=Math.addExact(actual,saving.amount());
            var progress=loan.storedRemainingPrincipal()==0?null:BigDecimal.valueOf(balance).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(loan.storedRemainingPrincipal()),2,RoundingMode.HALF_UP);
            var items=snapshot.savings().stream().filter(s->!s.savedAt().isBefore(from)&&!s.savedAt().isAfter(to))
                .map(s->new SavingList.Item(s.id(),s.loanId(),s.savedAt(),s.amount(),loan.storedStatus()==LoanStatus.ACTIVE&&!s.savedAt().isBefore(loan.latestSnapshotDate()),
                    s.createdAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime())).toList();
            return new SavingList(id,balance,required,planned,actual,progress,loan.storedRemainingPrincipal(),items);
        } catch(ArithmeticException|IllegalArgumentException ex) {throw ApiException.unavailable();}
    }
    private static LocalDate date(String value,LocalDate fallback) {
        if(value==null)return fallback;
        if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw invalid();
        var date=LocalDate.parse(value);if(date.getYear()<1)throw invalid();return date;
    }
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"SAVING_VALIDATION_ERROR","대출 유형과 조회 기간을 확인해 주세요.");}
}
