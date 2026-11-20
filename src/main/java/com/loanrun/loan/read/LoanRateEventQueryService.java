package com.loanrun.loan.read;

import com.loanrun.common.contract.FinanceModels.DateRange;
import com.loanrun.common.contract.LoanEventQueryPort;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Preserved source changes for risk/report consumers, including paid and deleted loans. */
@Service
@Transactional(readOnly=true)
public class LoanRateEventQueryService implements LoanEventQueryPort {
    private final NamedParameterJdbcTemplate jdbc;

    public LoanRateEventQueryService(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Override
    public List<Event> rateChanges(long userId,DateRange range) {
        if(userId<=0)throw new IllegalArgumentException("Positive internal owner required");
        Objects.requireNonNull(range);
        // Compute predecessors BEFORE the requested lower bound is applied. Current loan columns
        // cannot establish a historical rate or monthly cash impact.
        return List.copyOf(jdbc.query("""
            WITH history AS (
                SELECT h.id,h.loan_id,h.effective_date,h.annual_rate,
                    lag(h.annual_rate) OVER (PARTITION BY h.loan_id ORDER BY h.effective_date,h.id) AS previous_rate
                FROM loan_rate_histories h
                JOIN loans l ON l.id=h.loan_id AND l.user_id=h.user_id
                WHERE h.user_id=:user AND h.effective_date<=:end
            )
            SELECT loan_id,effective_date,annual_rate-previous_rate AS rate_change_pp
            FROM history
            WHERE effective_date>=:start AND previous_rate IS NOT NULL AND annual_rate<>previous_rate
            ORDER BY effective_date,id
            """,Map.of("user",userId,"start",range.startInclusive(),"end",range.endInclusive()),
            (rs,row)->new Event(rs.getLong("loan_id"),rs.getObject("effective_date",LocalDate.class),
                rs.getBigDecimal("rate_change_pp"),null)));
    }
}
