package com.loanrun.payment.repository;

import com.loanrun.common.contract.FinanceModels.DateRange;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSources.PaymentKind;
import com.loanrun.payment.read.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PaymentActualsRepository {
    private static final String SUMS="""
        count(*) AS payment_count,
        count(*) FILTER (WHERE p.payment_type='REGULAR') AS regular_count,
        count(*) FILTER (WHERE p.payment_type='EXTRA') AS extra_payment_count,
        count(*) FILTER (WHERE p.extra_amount>0) AS extra_principal_count,
        coalesce(sum(p.amount),0) AS amount,coalesce(sum(p.principal_amount),0) AS principal,
        coalesce(sum(p.extra_amount),0) AS extra,coalesce(sum(p.interest_amount),0) AS interest,
        coalesce(sum(p.fee_amount),0) AS fee,coalesce(sum(p.savings_used_amount),0) AS savings_used,
        coalesce(sum(p.amount-p.savings_used_amount),0) AS cash_paid
        """;
    private final NamedParameterJdbcTemplate jdbc;
    public PaymentActualsRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    public PaymentActualTotals aggregate(long userId,Long loanId,LocalDate from,LocalDate to,PaymentKind type) {
        var filter=PaymentSqlFilter.create(userId,loanId,from,to,type);requireLoan(userId,loanId);
        return jdbc.queryForObject("SELECT "+SUMS+" FROM payments p"+filter.where(),filter.parameters(),(rs,i)->totals(rs));
    }
    public List<PaymentActualMonth> monthly(long userId,DateRange range,Long loanId) {
        Objects.requireNonNull(range);
        var filter=PaymentSqlFilter.create(userId,loanId,range.startInclusive(),range.endInclusive(),null);requireLoan(userId,loanId);
        return List.copyOf(jdbc.query("SELECT date_trunc('month',p.paid_at)::date AS month,p.loan_id,"+SUMS+
            " FROM payments p"+filter.where()+" GROUP BY date_trunc('month',p.paid_at)::date,p.loan_id ORDER BY month,p.loan_id",
            filter.parameters(),(rs,i)->new PaymentActualMonth(YearMonth.from(rs.getObject("month",LocalDate.class)),rs.getLong("loan_id"),totals(rs))));
    }
    private void requireLoan(long userId,Long loanId) {
        if(loanId!=null&&(loanId<=0||jdbc.queryForObject("SELECT count(*) FROM loans WHERE user_id=:user AND id=:loan",
            Map.of("user",userId,"loan",loanId),Long.class)==0))
            throw new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
    }
    private static PaymentActualTotals totals(ResultSet rs)throws SQLException {
        try {return new PaymentActualTotals(rs.getLong("payment_count"),rs.getLong("regular_count"),rs.getLong("extra_payment_count"),
            rs.getLong("extra_principal_count"),exact(rs,"amount"),exact(rs,"principal"),exact(rs,"extra"),exact(rs,"interest"),
            exact(rs,"fee"),exact(rs,"savings_used"),exact(rs,"cash_paid"));}
        catch(ArithmeticException|IllegalArgumentException ex){throw ApiException.unavailable();}
    }
    private static long exact(ResultSet rs,String column)throws SQLException {return rs.getBigDecimal(column).longValueExact();}
}
