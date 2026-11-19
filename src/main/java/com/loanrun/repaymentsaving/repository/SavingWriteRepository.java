package com.loanrun.repaymentsaving.repository;

import com.loanrun.common.error.ApiException;
import java.sql.Timestamp;
import java.time.*;
import com.loanrun.loan.read.LoanSources.Saving;
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
    public record LockedSaving(Loan loan,Saving saving) {}
    public LockedSaving lockSaving(long userId,long savingId) {
        var ids=jdbc.query("SELECT loan_id FROM repayment_savings WHERE user_id=? AND id=?",(rs,i)->rs.getLong(1),userId,savingId);
        if(ids.isEmpty())throw missing();
        var loan=lock(userId,ids.getFirst());
        var rows=jdbc.query("SELECT * FROM repayment_savings WHERE user_id=? AND loan_id=? AND id=? FOR UPDATE",
            (rs,i)->new Saving(rs.getLong("id"),rs.getLong("user_id"),rs.getLong("loan_id"),rs.getObject("saved_at",LocalDate.class),
                rs.getLong("amount"),rs.getString("memo"),rs.getTimestamp("created_at").toInstant()),userId,loan.id(),savingId);
        if(rows.isEmpty())throw missing();
        return new LockedSaving(loan,rows.getFirst());
    }
    public void update(long userId,Saving original,LocalDate date,long amount) {
        int changed=jdbc.update("""
            UPDATE repayment_savings SET saved_at=?,amount=?
            WHERE user_id=? AND loan_id=? AND id=? AND saved_at=? AND amount=?
            """,date,amount,userId,original.loanId(),original.id(),original.savedAt(),original.amount());
        if(changed!=1)throw new ApiException(HttpStatus.CONFLICT,"SAVING_CONFLICT","적립 잔액 또는 이미 사용된 금액과 충돌합니다.");
    }
    public void delete(long userId,Saving original) {
        int changed=jdbc.update("DELETE FROM repayment_savings WHERE user_id=? AND loan_id=? AND id=? AND saved_at=? AND amount=?",
            userId,original.loanId(),original.id(),original.savedAt(),original.amount());
        if(changed!=1)throw new ApiException(HttpStatus.CONFLICT,"SAVING_CONFLICT","적립 잔액 또는 이미 사용된 금액과 충돌합니다.");
    }
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"SAVING_NOT_FOUND","리소스를 찾을 수 없습니다.");}
}
