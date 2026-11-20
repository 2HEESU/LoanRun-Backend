package com.loanrun.loan.read;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.payment.service.PaymentActualsQueryService;
import com.loanrun.payment.service.PaymentObservationQueryService;
import com.loanrun.repaymentsaving.service.SavingObservationQueryService;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Actual monthly history. No budget/plan calls and no inferred recording-completion evidence. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class LoanHistoryQueryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final PaymentActualsQueryService actuals;
    private final PaymentObservationQueryService payments;
    private final SavingObservationQueryService savings;
    private final Clock clock;
    public LoanHistoryQueryService(NamedParameterJdbcTemplate jdbc,PaymentActualsQueryService actuals,
        PaymentObservationQueryService payments,SavingObservationQueryService savings,Clock clock) {
        this.jdbc=jdbc;this.actuals=actuals;this.payments=payments;this.savings=savings;this.clock=clock;
    }
    public record Loan(long id,boolean bullet) {}
    public List<Loan> loans(long user,Long id) {
        owner(user);var p=new HashMap<String,Object>();p.put("user",user);if(id!=null)p.put("id",id);
        var result=jdbc.query("SELECT id,repayment_method FROM loans WHERE user_id=:user"+(id==null?"":" AND id=:id")+" ORDER BY id",p,
            (r,n)->new Loan(r.getLong("id"),"BULLET".equals(r.getString("repayment_method"))));
        if(id!=null&&result.isEmpty())throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
        return List.copyOf(result);
    }
    public List<HistoryMonth> history(long user,Long id,DateRange range) {
        Objects.requireNonNull(range);var loans=loans(user,id);var now=clock.instant();
        var current=YearMonth.from(now.atZone(ZoneId.of("Asia/Seoul")));var rows=new ArrayList<HistoryMonth>();
        for(var month=YearMonth.from(range.startInclusive());!month.isAfter(YearMonth.from(range.endInclusive()))&&!month.isAfter(current);month=month.plusMonths(1)) {
            // A partial date range cannot establish a complete calendar-month statistic.
            if(month.atDay(1).isBefore(range.startInclusive())||month.atEndOfMonth().isAfter(range.endInclusive()))continue;
            if(!payments.observeAt(user,month,id,now).observed())continue;
            var future=new HashMap<String,Object>();future.put("user",user);future.put("today",now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate());future.put("to",month.atEndOfMonth());
            if(id!=null)future.put("loan",id);
            String scope=id==null?"":" AND loan_id=:loan";
            if(jdbc.queryForObject("SELECT (SELECT count(*) FROM payments WHERE user_id=:user AND paid_at>:today AND paid_at<=:to"+scope+")+(SELECT count(*) FROM repayment_savings WHERE user_id=:user AND saved_at>:today AND saved_at<=:to"+scope+")",future,Long.class)>0)throw ApiException.unavailable();
            boolean complete=true;long additions=0;
            for(var loan:loans)if(loan.bullet()&&hasBasis(user,loan.id(),month.atEndOfMonth())) {
                var proof=savings.observeAt(user,loan.id(),month,now);
                if(!proof.observed()){complete=false;break;}
                additions=Math.addExact(additions,proof.actuals().additions());
            }
            if(!complete)continue;
            long opening=0,registered=0;
            for(var loan:loans) {
                var p=Map.of("user",user,"loan",loan.id(),"from",month.atDay(1),"to",month.atEndOfMonth());
                // Corrections during the month cannot be represented by HistoryMonth's registration field.
                if(jdbc.queryForObject("SELECT count(*) FROM loan_balance_snapshots WHERE user_id=:user AND loan_id=:loan AND effective_date BETWEEN :from AND :to AND reason='CORRECTION'",p,Long.class)>0)
                    throw ApiException.unavailable();
                opening=Math.addExact(opening,opening(user,loan.id(),month.atDay(1)));
                var bases=jdbc.queryForList("SELECT amount FROM loan_balance_snapshots WHERE user_id=:user AND loan_id=:loan AND effective_date BETWEEN :from AND :to AND reason='REGISTRATION'",p,Long.class);
                if(bases.size()>1)throw ApiException.unavailable();
                if(!bases.isEmpty())registered=Math.addExact(registered,bases.getFirst());
            }
            var a=actuals.aggregate(user,new DateRange(month.atDay(1),month.atEndOfMonth()),id);
            rows.add(new HistoryMonth(month,opening,registered,a.regularPrincipal(),a.extraPrincipal(),a.interest(),a.fee(),
                Math.toIntExact(a.extraPrincipalCount()),additions,a.savingsUsed(),true));
        }
        return List.copyOf(rows);
    }
    private boolean hasBasis(long user,long loan,LocalDate through) {
        return jdbc.queryForObject("SELECT count(*) FROM loan_balance_snapshots WHERE user_id=:user AND loan_id=:loan AND effective_date<=:through",
            Map.of("user",user,"loan",loan,"through",through),Long.class)>0;
    }
    private record Balance(long amount,LocalDate date,String reason,Instant recorded) {}
    private record Principal(LocalDate date,long amount,Instant recorded) {}
    private long opening(long user,long loan,LocalDate first) {
        var p=Map.of("user",user,"loan",loan,"first",first);
        if(jdbc.queryForObject("SELECT count(*) FROM loan_balance_snapshots WHERE user_id=:user AND loan_id=:loan AND reason='REGISTRATION'",p,Long.class)!=1)throw ApiException.unavailable();
        var bases=jdbc.query("SELECT amount,effective_date,reason,created_at FROM loan_balance_snapshots WHERE user_id=:user AND loan_id=:loan AND effective_date<:first ORDER BY effective_date DESC,id DESC LIMIT 1",p,
            (r,n)->new Balance(r.getLong("amount"),r.getObject("effective_date",LocalDate.class),r.getString("reason"),r.getTimestamp("created_at").toInstant()));
        if(bases.isEmpty()) {
            // Zero is structural only before an actual registration basis, never a missing baseline.
            if(jdbc.queryForObject("SELECT count(*) FROM loan_balance_snapshots WHERE user_id=:user AND loan_id=:loan AND reason='REGISTRATION' AND effective_date>=:first",p,Long.class)==1)return 0;
            throw ApiException.unavailable();
        }
        var base=bases.getFirst();long result=base.amount();
        var q=new HashMap<String,Object>(p);q.put("date",base.date());
        var rows=jdbc.query("SELECT paid_at,principal_amount+extra_amount AS principal,created_at FROM payments WHERE user_id=:user AND loan_id=:loan AND paid_at>=:date AND paid_at<:first ORDER BY paid_at,id",q,
            (r,n)->new Principal(r.getObject("paid_at",LocalDate.class),r.getBigDecimal("principal").longValueExact(),r.getTimestamp("created_at").toInstant()));
        for(var row:rows) {
            if(row.date().equals(base.date())&&!base.reason().equals("REGISTRATION")) {
                if(row.recorded().equals(base.recorded())&&row.amount()>0)throw ApiException.unavailable();
                if(row.recorded().isBefore(base.recorded()))continue;
            }
            result=Math.subtractExact(result,row.amount());if(result<0)throw ApiException.unavailable();
        }
        return result;
    }
    private static void owner(long user){if(user<=0)throw new IllegalArgumentException("Positive internal owner required");}
}
