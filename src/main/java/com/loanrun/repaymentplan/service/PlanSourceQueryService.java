package com.loanrun.repaymentplan.service;

import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.read.LoanSources.Snapshot;
import com.loanrun.repaymentplan.domain.SavedPlanSource;
import com.loanrun.repaymentplan.domain.SavedPlanSource.Origin;
import com.loanrun.repaymentplan.repository.PlanVersionRepository;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal source composition, not a partial PlanQueryPort or a performance/prediction calculator. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PlanSourceQueryService {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private final LoanSourceRepository loans;
    private final PlanVersionRepository versions;
    private final Clock clock;
    public PlanSourceQueryService(LoanSourceRepository loans,PlanVersionRepository versions,Clock clock) {
        this.loans=loans;this.versions=versions;this.clock=clock;
    }
    /** Explicit V2-only access; makes no assertion that no later versions exist. */
    public SavedPlanSource registration(long userId,long loanId) {
        var now=clock.instant();return baseline(userId,loanId,snapshot(userId,loanId),now.plusNanos(1),now);
    }
    /** Goals on an installment date, using only sources known through the separate observation date. */
    public Optional<SavedPlanSource> forDate(long userId,long loanId,LocalDate goalDate,LocalDate knownOn) {
        var now=clock.instant();var today=now.atZone(KST).toLocalDate();
        if(goalDate==null||knownOn==null||goalDate.getYear()<1||goalDate.getYear()>9999||knownOn.getYear()<1||knownOn.isAfter(today))
            throw new IllegalArgumentException("Invalid plan query dates");
        var limit=knownOn.plusDays(1).atStartOfDay(KST).toInstant();
        var source=snapshot(userId,loanId);var baseline=baseline(userId,loanId,source,limit,now);
        if(baseline.recordedAt().compareTo(limit)>=0||baseline.recordedAt().isAfter(now))return Optional.empty();
        try {
            var selected=versions.versions(userId,loanId).stream()
                .filter(v->!v.draft().effectiveDate().isAfter(goalDate)&&v.createdAt().isBefore(limit)&&!v.createdAt().isAfter(now))
                .max(Comparator.comparingLong(com.loanrun.repaymentplan.domain.PlanVersion::version));
            if(selected.isPresent()) {
                var v=selected.get();var d=v.draft();
                return Optional.of(new SavedPlanSource(userId,loanId,v.version(),Origin.PLAN_VERSION,true,d.mode().name(),
                    d.preference()==null?null:d.preference().name(),d.effectiveDate(),d.targetDate(),d.targetPayoffDate(),
                    d.monthlyExtraCash(),d.monthlySaving(),d.termsAtStart().outstanding(),d.termsAtStart(),d.inputs(),d.strategy(),v.createdAt()));
            }
        } catch(DataAccessException e) {throw ApiException.unavailable();} // Missing V4 is not proof of an empty history.
        return baseline.effectiveDate().isAfter(goalDate)?Optional.empty():Optional.of(baseline);
    }
    private Snapshot snapshot(long userId,long loanId) {
        var snapshot=loans.loanSnapshot(userId,loanId,LocalDate.now(clock.withZone(KST)));
        if(snapshot.loans().isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
        return snapshot;
    }
    private SavedPlanSource baseline(long userId,long loanId,Snapshot source,Instant limit,Instant now) {
        var loan=source.loans().getFirst();var initial=loan.initialPlan();
        if(initial.version()!=1||initial.loanId()!=loanId||initial.mode()==null||initial.effectiveDate()==null)throw ApiException.unavailable();
        var balances=source.balances().stream().filter(b->b.loanId()==loanId&&b.reason().equals("REGISTRATION")).toList();
        if(balances.size()>1)throw ApiException.unavailable();
        Long start=balances.isEmpty()||!balances.getFirst().createdAt().isBefore(limit)||balances.getFirst().createdAt().isAfter(now)?null:balances.getFirst().amount();
        return new SavedPlanSource(userId,loanId,1,Origin.REGISTRATION,initial.configured(),initial.mode(),initial.preference(),
            initial.effectiveDate(),null,initial.targetPayoffDate(),initial.monthlyExtraCash(),initial.monthlySaving(),start,null,null,null,loan.createdAt());
    }
}
