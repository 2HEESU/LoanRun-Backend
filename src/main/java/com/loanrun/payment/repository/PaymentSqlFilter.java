package com.loanrun.payment.repository;

import com.loanrun.loan.read.LoanSources.PaymentKind;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Shared bound predicates for totals and page selection. SQL syntax comes only from these constants. */
record PaymentSqlFilter(String where,Map<String,Object> parameters) {
    PaymentSqlFilter { parameters=Map.copyOf(parameters); }
    static PaymentSqlFilter create(long userId,Long loanId,LocalDate from,LocalDate to,PaymentKind type) {
        if(userId<=0)throw new IllegalArgumentException("Positive internal owner required");
        if(from!=null&&to!=null&&from.isAfter(to))throw new IllegalArgumentException("Invalid actual date range");
        var p=new HashMap<String,Object>();p.put("user",userId);
        var where=new StringBuilder(" WHERE p.user_id=:user");
        if(loanId!=null){where.append(" AND p.loan_id=:loan");p.put("loan",loanId);}
        if(from!=null){where.append(" AND p.paid_at>=:from");p.put("from",from);}
        if(to!=null){where.append(" AND p.paid_at<=:to");p.put("to",to);}
        if(type!=null){where.append(" AND p.payment_type=:type");p.put("type",type.name());}
        return new PaymentSqlFilter(where.toString(),p);
    }
}
