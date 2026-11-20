package com.loanrun.repaymentsaving.repository;

import com.loanrun.common.error.ApiException;
import com.loanrun.repaymentsaving.read.SavingObservation.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class SavingObservationSnapshotRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public SavingObservationSnapshotRepository(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}

    public Snapshot snapshot(Scope scope) {
        var parameters=Map.of("user",scope.userId(),"loan",scope.loanId(),"from",scope.month().atDay(1),"to",scope.month().atEndOfMonth());
        return jdbc.query("""
            SELECT l.repayment_method,s.id,s.saved_at,s.amount FROM loans l
            LEFT JOIN repayment_savings s ON s.user_id=l.user_id AND s.loan_id=l.id AND s.saved_at BETWEEN :from AND :to
            WHERE l.user_id=:user AND l.id=:loan ORDER BY s.id
            """,parameters,rs->{
                if(!rs.next())throw new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
                if(!"BULLET".equals(rs.getString("repayment_method")))throw new IllegalArgumentException("Saving observation requires BULLET");
                var digest=sha256();update(digest,"SAVING_ADDITION_MONTH_V1\n"+scope.userId()+"|"+scope.loanId()+"|"+scope.month()+"\n");
                long count=0,amount=0;
                do {
                    if(rs.getObject("id")==null)continue;
                    long value=rs.getLong("amount");
                    if(value<=0)throw ApiException.unavailable();
                    count=Math.incrementExact(count);amount=Math.addExact(amount,value);
                    update(digest,rs.getLong("id")+"|"+rs.getObject("saved_at",LocalDate.class)+"|"+value+"\n");
                } while(rs.next());
                return new Snapshot(scope,HexFormat.of().formatHex(digest.digest()),new Recorded(count,amount));
            });
    }
    private static MessageDigest sha256() {
        try{return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException("Required digest unavailable",e);}
    }
    private static void update(MessageDigest digest,String value){digest.update(value.getBytes(StandardCharsets.UTF_8));}
}
