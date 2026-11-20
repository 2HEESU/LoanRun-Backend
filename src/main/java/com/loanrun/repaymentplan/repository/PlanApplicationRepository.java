package com.loanrun.repaymentplan.repository;

import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PlanApplicationRepository {
    public record State(boolean exists,String code,Instant appliedAt,long revision,List<PlannedAmount> allocations) {}
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    public PlanApplicationRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public Optional<State> latest(long user) {
        return query(user,null);
    }
    public Optional<State> latestBefore(long user,Instant cutoff) {
        return query(user,Objects.requireNonNull(cutoff));
    }
    private Optional<State> query(long user,Instant cutoff) {
        var args=cutoff==null?new Object[]{user}:new Object[]{user,Timestamp.from(cutoff)};
        try{return jdbc.query("SELECT * FROM plan_application_events WHERE user_id=?"+(cutoff==null?"":" AND applied_at<=?")+" ORDER BY id DESC LIMIT 1",(r,i)->{
            try{return new State(r.getBoolean("overall_exists"),r.getString("strategy_code"),r.getTimestamp("applied_at").toInstant(),r.getLong("input_revision"),List.of(mapper.readValue(r.getString("allocations"),PlannedAmount[].class)));}
            catch(java.io.IOException e){throw ApiException.unavailable();}
        },args).stream().findFirst();}catch(org.springframework.dao.DataAccessException e){throw ApiException.unavailable();}
    }
    public void append(long user,long revision,boolean exists,String code,List<PlannedAmount> allocations,Instant now) {
        try{jdbc.update("INSERT INTO plan_application_events(user_id,input_revision,overall_exists,strategy_code,allocations,applied_at) VALUES (?,?,?,?,CAST(? AS jsonb),?)",user,revision,exists,code,mapper.writeValueAsString(allocations),Timestamp.from(now));}
        catch(java.io.IOException|org.springframework.dao.DataAccessException e){throw ApiException.unavailable();}
    }
}
