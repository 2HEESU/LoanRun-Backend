package com.loanrun.payment.repository;

import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSources.Payment;
import com.loanrun.loan.read.LoanSources.PaymentKind;
import com.loanrun.payment.read.PaymentDetailSource;
import com.loanrun.payment.read.PaymentListFilter;
import com.loanrun.payment.read.PaymentListSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PaymentReadRepository {
    private static final String DETAIL_SELECT="""
        SELECT p.*,l.snapshot_date,d.id AS allocated_due_id,d.due_date AS allocated_due_date,
            a.principal_amount AS allocated_principal,a.interest_amount AS allocated_interest,
            a.fee_amount AS allocated_fee
        FROM %s p
        JOIN loans l ON l.user_id=p.user_id AND l.id=p.loan_id
        LEFT JOIN payment_allocations a ON a.user_id=p.user_id AND a.loan_id=p.loan_id AND a.payment_id=p.id
        LEFT JOIN due_items d ON d.user_id=a.user_id AND d.loan_id=a.loan_id AND d.id=a.due_item_id
        """;
    private final NamedParameterJdbcTemplate jdbc;
    private final PaymentActualsRepository actuals;
    public PaymentReadRepository(NamedParameterJdbcTemplate jdbc,PaymentActualsRepository actuals) { this.jdbc=jdbc;this.actuals=actuals; }

    /** One statement avoids mixing payment, snapshot and allocation states from separate reads. */
    public PaymentDetailSource detail(long userId,long paymentId) {
        if(userId<=0)throw new IllegalArgumentException("Positive internal owner required");
        if(paymentId<=0)throw missing();
        var rows=jdbc.query(DETAIL_SELECT.formatted("payments")+"""
            WHERE p.user_id=:user AND p.id=:payment
            ORDER BY d.due_date,d.id
            """,Map.of("user",userId,"payment",paymentId),PaymentReadRepository::readRows);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }

    /** Aggregate and page share one read-only repeatable-read transaction; joins never multiply totals. */
    public PaymentListSource list(long userId,PaymentListFilter filter) {
        var selection=PaymentSqlFilter.create(userId,filter.loanId(),filter.from(),filter.to(),filter.type());
        var params=new HashMap<>(selection.parameters());var where=selection.where();
        var totals=actuals.aggregate(userId,filter.loanId(),filter.from(),filter.to(),filter.type());
        params.put("limit",filter.pageSize());params.put("offset",filter.offset());
        List<PaymentDetailSource> rows=totals.paymentCount()<=filter.offset()?List.of():jdbc.query("WITH selected AS (SELECT p.* FROM payments p"+where+" "+"""
            ORDER BY p.paid_at DESC,p.id DESC LIMIT :limit OFFSET :offset)
            """+DETAIL_SELECT.formatted("selected")+" ORDER BY p.paid_at DESC,p.id DESC,d.due_date,d.id",params,PaymentReadRepository::readRows);
        return new PaymentListSource(totals.paymentCount(),totals.amount(),totals.regularPrincipal(),totals.interest(),totals.extraPrincipal(),totals.fee(),totals.savingsUsed(),rows);
    }

    /** Rows must be contiguous per payment, then ordered by its due date/ID. */
    private static List<PaymentDetailSource> readRows(ResultSet rs)throws SQLException {
        var results=new ArrayList<PaymentDetailSource>();
        Payment payment=null;LocalDate snapshotDate=null;
        var allocations=new ArrayList<PaymentDetailSource.Allocation>();
        while(rs.next()) {
            if(payment==null||payment.id()!=rs.getLong("id")) {
                if(payment!=null)results.add(new PaymentDetailSource(payment,snapshotDate,allocations));
                allocations=new ArrayList<>();
                payment=new Payment(rs.getLong("id"),rs.getLong("user_id"),rs.getLong("loan_id"),rs.getObject("paid_at",LocalDate.class),
                    PaymentKind.valueOf(rs.getString("payment_type")),rs.getLong("amount"),rs.getLong("principal_amount"),rs.getLong("extra_amount"),
                    rs.getLong("interest_amount"),rs.getLong("fee_amount"),rs.getLong("savings_used_amount"),rs.getString("memo"),rs.getTimestamp("created_at").toInstant());
                snapshotDate=rs.getObject("snapshot_date",LocalDate.class);
            }
            if(rs.getObject("allocated_due_id")!=null)allocations.add(new PaymentDetailSource.Allocation(rs.getLong("allocated_due_id"),
                rs.getObject("allocated_due_date",LocalDate.class),rs.getLong("allocated_principal"),rs.getLong("allocated_interest"),rs.getLong("allocated_fee")));
        }
        if(payment!=null)results.add(new PaymentDetailSource(payment,snapshotDate,allocations));
        return List.copyOf(results);
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND,"PAYMENT_NOT_FOUND","납부 기록을 찾을 수 없습니다.");
    }
}
