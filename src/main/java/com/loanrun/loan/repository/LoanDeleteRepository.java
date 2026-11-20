package com.loanrun.loan.repository;

import com.loanrun.common.error.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class LoanDeleteRepository {
    private final JdbcTemplate jdbc;
    public LoanDeleteRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Loan(long id,String status) {}
    /** The caller holds the common user lock before this owner-scoped loan lock. */
    public Loan lock(long userId,long loanId) {
        var rows=jdbc.query("SELECT id,status FROM loans WHERE user_id=? AND id=? AND status<>'DELETED' FOR UPDATE",
            (rs,i)->new Loan(rs.getLong("id"),rs.getString("status")),userId,loanId);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    public void rejectPayments(long userId,long loanId) {
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payments WHERE user_id=? AND loan_id=?)",Boolean.class,userId,loanId)))throw hasPayments();
    }
    public void markDeleted(long userId,Loan loan,Instant now) {
        int changed=jdbc.update("""
            UPDATE loans SET status='DELETED',updated_at=? WHERE user_id=? AND id=? AND status=?
              AND NOT EXISTS(SELECT 1 FROM payments WHERE user_id=? AND loan_id=?)
            """,Timestamp.from(now),userId,loan.id(),loan.status(),userId,loan.id());
        if(changed!=1)throw hasPayments();
    }
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");}
    private static ApiException hasPayments(){return new ApiException(HttpStatus.CONFLICT,"LOAN_HAS_PAYMENTS","상환 내역이 있는 대출은 삭제할 수 없습니다.");}
}
