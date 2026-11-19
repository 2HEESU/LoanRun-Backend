package com.loanrun.payment.repository;
import com.loanrun.payment.domain.PaymentReplay.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.loan.read.LoanSources.Due;
import com.loanrun.common.error.ApiException;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentWriteRepository {
    private final NamedParameterJdbcTemplate jdbc;private final ObjectMapper mapper;
    public PaymentWriteRepository(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public long nextId(){return jdbc.getJdbcTemplate().queryForObject("SELECT nextval(pg_get_serial_sequence('payments','id'))",Long.class);}
    public void insert(long user,long loan,Entry e) {
        var p=e.request();jdbc.update("INSERT INTO payments(id,user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at) VALUES (:id,:user,:loan,:date,:type,:amount,:principal,:extra,:interest,:fee,:used,:created)",
            new MapSqlParameterSource().addValue("id",p.id()).addValue("user",user).addValue("loan",loan).addValue("date",p.date()).addValue("type",p.kind().name()).addValue("amount",p.amount()).addValue("principal",e.principal()).addValue("extra",e.extra()).addValue("interest",e.interest()).addValue("fee",e.fee()).addValue("used",p.used()).addValue("created",Timestamp.from(p.createdAt())));
    }
    public void allocations(long user,long loan,Result replay) {
        if(!replay.mutableIds().isEmpty())jdbc.update("DELETE FROM payment_allocations WHERE user_id=:user AND loan_id=:loan AND payment_id IN (:ids)",Map.of("user",user,"loan",loan,"ids",replay.mutableIds()));
        for(var a:replay.allocations())if(replay.mutableIds().contains(a.payment()))jdbc.update("INSERT INTO payment_allocations(user_id,loan_id,payment_id,due_item_id,principal_amount,interest_amount,fee_amount) VALUES (:user,:loan,:payment,:due,:principal,:interest,:fee)",Map.of("user",user,"loan",loan,"payment",a.payment(),"due",a.due(),"principal",a.principal(),"interest",a.interest(),"fee",a.fee()));
    }
    public void archiveDue(long user,long loan,Due due,Instant now) {
        try{jdbc.update("INSERT INTO payment_due_archives(user_id,loan_id,due_date,due_snapshot,archived_at) VALUES (:user,:loan,:date,CAST(:json AS jsonb),:now)",new MapSqlParameterSource().addValue("user",user).addValue("loan",loan).addValue("date",due.dueDate()).addValue("json",mapper.writeValueAsString(due)).addValue("now",Timestamp.from(now)));}catch(java.io.IOException e){throw ApiException.unavailable();}
    }
    public boolean restoreDue(long user,long loan,LocalDate date) {
        var rows=jdbc.queryForList("SELECT due_snapshot::text FROM payment_due_archives WHERE user_id=:user AND loan_id=:loan AND due_date=:date ORDER BY id DESC LIMIT 1",Map.of("user",user,"loan",loan,"date",date),String.class);if(rows.isEmpty())return false;
        try{var d=mapper.readValue(rows.getFirst(),Due.class);if(d.userId()!=user||d.loanId()!=loan||!d.dueDate().equals(date))throw ApiException.unavailable();
            jdbc.update("INSERT INTO due_items(id,user_id,loan_id,due_date,principal_amount,interest_amount,fee_amount,contractual_amount,required_amount,confirmed_unpaid,terms_revision,created_at) VALUES (:id,:user,:loan,:date,:principal,:interest,:fee,:contract,:required,:confirmed,:revision,:created)",new MapSqlParameterSource().addValue("id",d.id()).addValue("user",user).addValue("loan",loan).addValue("date",d.dueDate()).addValue("principal",d.estimatedPrincipal()).addValue("interest",d.estimatedInterest()).addValue("fee",d.fee()).addValue("contract",d.contractualAmount()).addValue("required",d.requiredAmount()).addValue("confirmed",d.confirmedUnpaid()).addValue("revision",d.termsRevision()).addValue("created",Timestamp.from(d.createdAt())));return true;
        }catch(java.io.IOException e){throw ApiException.unavailable();}
    }

}
