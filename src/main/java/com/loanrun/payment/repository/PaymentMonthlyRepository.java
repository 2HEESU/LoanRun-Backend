package com.loanrun.payment.repository;

import com.loanrun.common.error.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Lightweight owner-scoped metadata/counts; unrelated saved plan JSON is never parsed. */
@Repository
public class PaymentMonthlyRepository {
    public record Loan(long id,boolean bullet,LocalDate registered,LocalDate maturity) {}
    public record Counts(long payments,long futurePayments,long futureSavings) {}
    private final NamedParameterJdbcTemplate jdbc;
    public PaymentMonthlyRepository(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Loan> loans(long user,Long id) {
        var p=new HashMap<String,Object>();p.put("user",user);if(id!=null)p.put("id",id);
        var rows=jdbc.query("SELECT id,repayment_method,(created_at AT TIME ZONE 'Asia/Seoul')::date AS registered,maturity_date FROM loans WHERE user_id=:user"+(id==null?"":" AND id=:id")+" ORDER BY id",p,
            (r,i)->new Loan(r.getLong("id"),"BULLET".equals(r.getString("repayment_method")),r.getObject("registered",LocalDate.class),r.getObject("maturity_date",LocalDate.class)));
        if(id!=null&&rows.isEmpty())throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");return List.copyOf(rows);
    }
    public Map<YearMonth,Counts> counts(long user,Long id,YearMonth from,YearMonth to,LocalDate today) {
        var p=new HashMap<String,Object>();p.put("user",user);p.put("from",from.atDay(1));p.put("to",to.atEndOfMonth());p.put("today",today);if(id!=null)p.put("loan",id);
        var scope=" WHERE user_id=:user AND %s BETWEEN :from AND :to"+(id==null?"":" AND loan_id=:loan");
        var sql="SELECT month,sum(payments) AS payments,sum(future_payments) AS future_payments,sum(future_savings) AS future_savings FROM ("+
            "SELECT date_trunc('month',paid_at)::date AS month,count(*) AS payments,count(*) FILTER(WHERE paid_at>:today) AS future_payments,0 AS future_savings FROM payments"+scope.formatted("paid_at")+" GROUP BY 1 UNION ALL "+
            "SELECT date_trunc('month',saved_at)::date AS month,0 AS payments,0 AS future_payments,count(*) FILTER(WHERE saved_at>:today) AS future_savings FROM repayment_savings"+scope.formatted("saved_at")+" GROUP BY 1) x GROUP BY month";
        Map<YearMonth,Counts> result=new HashMap<>();jdbc.query(sql,p,(org.springframework.jdbc.core.RowCallbackHandler)r->result.put(YearMonth.from(r.getObject("month",LocalDate.class)),new Counts(r.getLong("payments"),r.getLong("future_payments"),r.getLong("future_savings"))));return Map.copyOf(result);
    }
}
