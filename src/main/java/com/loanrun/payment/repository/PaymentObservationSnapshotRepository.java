package com.loanrun.payment.repository;

import com.loanrun.payment.read.PaymentObservation.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDate;
import java.util.HexFormat;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PaymentObservationSnapshotRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final PaymentActualsRepository actuals;
    public PaymentObservationSnapshotRepository(NamedParameterJdbcTemplate jdbc,PaymentActualsRepository actuals) {this.jdbc=jdbc;this.actuals=actuals;}

    /** Aggregates and canonical source rows share a snapshot. No global user revision is used:
     * another month's financial activity must not invalidate an unchanged month's completion evidence.
     */
    public Snapshot snapshot(Scope scope) {
        var month=scope.month();var from=month.atDay(1);var to=month.atEndOfMonth();
        var recorded=actuals.aggregate(scope.userId(),scope.loanId(),from,to,null);
        var filter=PaymentSqlFilter.create(scope.userId(),scope.loanId(),from,to,null);
        var digest=sha256();
        update(digest,"PAYMENT_MONTH_V1\n"+scope.userId()+"|"+(scope.loanId()==null?"ALL":scope.loanId())+"|"+month+"\n");
        jdbc.query("""
            SELECT p.id,p.loan_id,p.paid_at,p.payment_type,p.amount,p.principal_amount,p.extra_amount,
                p.interest_amount,p.fee_amount,p.savings_used_amount FROM payments p
            """+filter.where()+" ORDER BY p.id",filter.parameters(),(RowCallbackHandler)rs->update(digest,
                rs.getLong("id")+"|"+rs.getLong("loan_id")+"|"+rs.getObject("paid_at",LocalDate.class)+"|"+rs.getString("payment_type")+"|"+
                rs.getLong("amount")+"|"+rs.getLong("principal_amount")+"|"+rs.getLong("extra_amount")+"|"+
                rs.getLong("interest_amount")+"|"+rs.getLong("fee_amount")+"|"+rs.getLong("savings_used_amount")+"\n"));
        return new Snapshot(scope,HexFormat.of().formatHex(digest.digest()),recorded);
    }
    private static MessageDigest sha256() {
        try{return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException("Required digest unavailable",ex);}
    }
    private static void update(MessageDigest digest,String value){digest.update(value.getBytes(StandardCharsets.UTF_8));}
}
