package com.loanrun.repaymentplan.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.repaymentplan.domain.PlanVersion;
import com.loanrun.repaymentplan.domain.PlanVersion.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Unnumbered SQL proposal only. No startup queries and no partial public query port implementation. */
@Repository
public class PlanVersionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserMutationLock locks;
    private final RevisionPort revisions;
    private final Clock clock;
    public PlanVersionRepository(JdbcTemplate jdbc,ObjectMapper mapper,UserMutationLock locks,RevisionPort revisions,Clock clock) {
        this.jdbc=jdbc;this.mapper=mapper;this.locks=locks;this.revisions=revisions;this.clock=clock;
    }
    /** Appends inside the coordinator's transaction; it alone bumps revision/recalculates/stores API replay. */
    @Transactional(propagation=Propagation.MANDATORY)
    public PlanVersion append(long userId,long expectedVersion,Draft draft) {
        if(userId<=0||expectedVersion<1||draft.termsAtStart().userId()!=userId)throw new IllegalArgumentException("Invalid source owner/version");
        locks.lock(userId);
        var rows=jdbc.query("SELECT * FROM loans WHERE user_id=? AND id=? FOR UPDATE",(r,n)->new Baseline(r.getString("status"),
            r.getLong("original_principal"),r.getLong("remaining_principal"),r.getString("initial_plan_snapshot")),userId,draft.loanId());
        if(rows.isEmpty())throw missing();var baseline=rows.getFirst();
        if(!baseline.status().equals("ACTIVE")||draft.inputRevision()!=revisions.current(userId)
            ||draft.termsAtStart().principalOriginal()!=baseline.original()||draft.termsAtStart().outstanding()!=baseline.balance())throw new Conflict();
        long initial=initialVersion(baseline.plan(),draft.loanId());
        Long latest=jdbc.queryForObject("SELECT max(version) FROM repayment_plan_versions WHERE user_id=? AND loan_id=?",Long.class,userId,draft.loanId());
        long current=latest==null?initial:latest;
        if(current!=expectedVersion)throw new Conflict();
        long next;
        try{next=Math.addExact(current,1);}catch(ArithmeticException e){throw new Conflict();}
        String terms=json(draft.termsAtStart());var strategy=draft.strategy();var now=clock.instant();
        long id=jdbc.queryForObject("""
            INSERT INTO repayment_plan_versions(user_id,loan_id,version,effective_date,input_data_revision,mode,preference,
                target_date,target_payoff_date,monthly_extra_cash,monthly_saving,requested_extra_monthly_payment,requested_monthly_amount,
                requested_saving_amount,plan_start_principal,terms_snapshot,
                source_report_id,source_strategy_version,source_strategy_code,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?) RETURNING id
            """,Long.class,userId,draft.loanId(),next,draft.effectiveDate(),draft.inputRevision(),draft.mode().name(),
            draft.preference()==null?null:draft.preference().name(),draft.targetDate(),draft.targetPayoffDate(),draft.monthlyExtraCash(),draft.monthlySaving(),
            draft.inputs().extraMonthlyPayment(),draft.inputs().monthlyAmount(),draft.inputs().plannedSavingAmount(),draft.termsAtStart().outstanding(),terms,
            strategy==null?null:strategy.reportId(),strategy==null?null:strategy.version(),strategy==null?null:strategy.code(),Timestamp.from(now));
        return jdbc.query("SELECT * FROM repayment_plan_versions WHERE user_id=? AND loan_id=? AND id=?",this::row,userId,draft.loanId(),id).getFirst();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<PlanVersion> versions(long userId,long loanId) {
        owner(userId,loanId);
        return List.copyOf(jdbc.query("SELECT * FROM repayment_plan_versions WHERE user_id=? AND loan_id=? ORDER BY version",this::row,userId,loanId));
    }
    /** Only proposed-table versions. An empty result does not erase V2's separate initial version. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Optional<PlanVersion> effectiveOn(long userId,long loanId,LocalDate date) {
        Objects.requireNonNull(date);owner(userId,loanId);
        return jdbc.query("SELECT * FROM repayment_plan_versions WHERE user_id=? AND loan_id=? AND effective_date<=? ORDER BY version DESC LIMIT 1",
            this::row,userId,loanId,date).stream().findFirst();
    }
    private record Baseline(String status,long original,long balance,String plan) {}
    private void owner(long userId,long loanId) {
        if(userId<=0||loanId<=0||!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM loans WHERE user_id=? AND id=?)",Boolean.class,userId,loanId)))throw missing();
    }
    private long initialVersion(String json,long loanId) {
        try {
            var source=mapper.readValue(json,PlanSnapshot.class);
            if(source==null||source.loanId()!=loanId||source.version()!=1)throw ApiException.unavailable();
            return source.version();
        } catch(JsonProcessingException|IllegalArgumentException e){throw ApiException.unavailable();}
    }
    private String json(Object value) {
        try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalArgumentException("Invalid plan terms");}
    }
    private PlanVersion row(ResultSet r,int ignored)throws SQLException {
        try {
            var terms=mapper.readValue(r.getString("terms_snapshot"),LoanTerms.class);
            var report=(Long)r.getObject("source_report_id");
            var strategy=report==null?null:new StrategySource(report,r.getString("source_strategy_version"),r.getString("source_strategy_code"));
            var preference=r.getString("preference");
            var draft=new Draft(r.getLong("loan_id"),Mode.valueOf(r.getString("mode")),preference==null?null:Preference.valueOf(preference),
                r.getObject("target_date",LocalDate.class),r.getObject("target_payoff_date",LocalDate.class),r.getLong("monthly_extra_cash"),r.getLong("monthly_saving"),
                r.getObject("effective_date",LocalDate.class),r.getLong("input_data_revision"),terms,
                new Inputs((Long)r.getObject("requested_extra_monthly_payment"),(Long)r.getObject("requested_monthly_amount"),(Long)r.getObject("requested_saving_amount")),strategy);
            if(terms.userId()!=r.getLong("user_id")||terms.outstanding()!=r.getLong("plan_start_principal"))throw ApiException.unavailable();
            return new PlanVersion(r.getLong("id"),r.getLong("user_id"),r.getLong("loan_id"),r.getLong("version"),draft,r.getTimestamp("created_at").toInstant());
        } catch(JsonProcessingException|IllegalArgumentException e){throw ApiException.unavailable();}
    }
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");}
}
