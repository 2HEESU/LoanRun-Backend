package com.loanrun.payment.repository;

import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSources.Payment;
import com.loanrun.loan.read.LoanSources.PaymentKind;
import com.loanrun.payment.read.PaymentDetailSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PaymentReadRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public PaymentReadRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** One statement avoids mixing payment, snapshot and allocation states from separate reads. */
    public PaymentDetailSource detail(long userId,long paymentId) {
        if(userId<=0)throw new IllegalArgumentException("Positive internal owner required");
        if(paymentId<=0)throw missing();
        return jdbc.query("""
            SELECT p.*,l.snapshot_date,d.id AS allocated_due_id,d.due_date AS allocated_due_date,
                a.principal_amount AS allocated_principal,a.interest_amount AS allocated_interest,
                a.fee_amount AS allocated_fee
            FROM payments p
            JOIN loans l ON l.user_id=p.user_id AND l.id=p.loan_id
            LEFT JOIN payment_allocations a ON a.user_id=p.user_id AND a.loan_id=p.loan_id AND a.payment_id=p.id
            LEFT JOIN due_items d ON d.user_id=a.user_id AND d.loan_id=a.loan_id AND d.id=a.due_item_id
            WHERE p.user_id=:user AND p.id=:payment
            ORDER BY d.due_date,d.id
            """,Map.of("user",userId,"payment",paymentId),rs->{
                if(!rs.next())throw missing();
                var payment=new Payment(rs.getLong("id"),rs.getLong("user_id"),rs.getLong("loan_id"),rs.getObject("paid_at",LocalDate.class),
                    PaymentKind.valueOf(rs.getString("payment_type")),rs.getLong("amount"),rs.getLong("principal_amount"),rs.getLong("extra_amount"),
                    rs.getLong("interest_amount"),rs.getLong("fee_amount"),rs.getLong("savings_used_amount"),rs.getString("memo"),rs.getTimestamp("created_at").toInstant());
                var snapshotDate=rs.getObject("snapshot_date",LocalDate.class);
                var allocations=new ArrayList<PaymentDetailSource.Allocation>();
                do {
                    if(rs.getObject("allocated_due_id")!=null)allocations.add(new PaymentDetailSource.Allocation(rs.getLong("allocated_due_id"),
                        rs.getObject("allocated_due_date",LocalDate.class),rs.getLong("allocated_principal"),rs.getLong("allocated_interest"),rs.getLong("allocated_fee")));
                } while(rs.next());
                return new PaymentDetailSource(payment,snapshotDate,allocations);
            });
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND,"PAYMENT_NOT_FOUND","납부 기록을 찾을 수 없습니다.");
    }
}
