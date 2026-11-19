package com.loanrun.repaymentplan.service;

import com.loanrun.common.error.ApiException;
import com.loanrun.common.contract.RepaymentEngine;
import com.loanrun.loan.service.CurrentLoanTermsQueryService;
import com.loanrun.loan.service.CurrentLoanTermsQueryService.Current;
import com.loanrun.repaymentplan.domain.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Actual source-to-engine composition; no HTTP adapter, writes or partial shared port. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PlanPayoffQueryService {
    private final CurrentLoanTermsQueryService loans;
    private final PlanSourceQueryService plans;
    private final PlanObservationQueryService observations;
    private final PlanPayoffProjection calculator;
    public PlanPayoffQueryService(CurrentLoanTermsQueryService loans,PlanSourceQueryService plans,PlanObservationQueryService observations,RepaymentEngine engine) {
        this.loans=loans;this.plans=plans;this.observations=observations;this.calculator=new PlanPayoffProjection(engine);
    }
    public enum Reason { AVAILABLE,LOAN_BASIS_UNAVAILABLE,NO_APPLICABLE_PLAN,MONTHLY_INPUT_UNAVAILABLE }
    public record Result(Current current,SavedPlanSource plan,PlanPayoffProjection.Projection projection,Reason reason) {}
    public Result current(long userId,long loanId) {
        try {
            var current=loans.current(userId,loanId);
            if(current.terms()==null)return new Result(current,null,null,Reason.LOAN_BASIS_UNAVAILABLE);
            var terms=current.terms();
            Optional<SavedPlanSource> plan=plans.forDate(userId,loanId,terms.nextDueDate(),current.asOf());
            if(plan.isEmpty())return new Result(current,null,null,Reason.NO_APPLICABLE_PLAN);
            var window=observations.current(userId,loanId);
            if(!window.asOf().equals(current.asOf())||window.method()!=terms.method())throw ApiException.unavailable();
            int horizon=Math.toIntExact(ChronoUnit.MONTHS.between(YearMonth.from(current.asOf()),YearMonth.from(terms.maturityDate()))+1);
            var projected=calculator.projectWithUpcomingPlan(terms,window.registeredOn(),current.asOf(),horizon,plan.get(),window.observations());
            return new Result(current,plan.get(),projected,projected.simulation()==null?Reason.MONTHLY_INPUT_UNAVAILABLE:Reason.AVAILABLE);
        } catch(ArithmeticException|IllegalArgumentException|DataAccessException e){throw ApiException.unavailable();}
    }
}
