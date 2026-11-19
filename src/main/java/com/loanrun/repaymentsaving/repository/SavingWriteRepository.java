package com.loanrun.repaymentsaving.repository;

import com.loanrun.common.error.ApiException;
import java.sql.Timestamp;
import java.time.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class SavingWriteRepository {
    public record Loan(long id,String status,String method,LocalDate snapshotDate) {}
    private final JdbcTemplate jdbc;
    public SavingWriteRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    /** The caller holds the user lock before taking the loan lock. */
    public Loan lock(long userId,long loanId) {
        var rows=jdbc.query("SELECT id,status,repayment_method,snapshot_date FROM loans WHERE user_id=? AND id=? FOR UPDATE",
            (rs,i)->new Loan(rs.getLong("id"),rs.getString("status"),rs.getString("repayment_method"),rs.getObject("snapshot_date",LocalDate.class)),userId,loanId);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"SAVING_NOT_FOUND","리소스를 찾을 수 없습니다.");
        return rows.getFirst();
    }
    public long insert(long userId,long loanId,LocalDate date,long amount,Instant now) {
        return jdbc.queryForObject("""
            INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,?,?,?) RETURNING id
            """,Long.class,userId,loanId,date,amount,Timestamp.from(now));
    }
}
