package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;

import com.loanrun.common.contract.BudgetRecalculationPort;
import com.loanrun.common.contract.FinanceModels.DateRange;
import com.loanrun.common.contract.LoanEventQueryPort;
import com.loanrun.loan.service.LoanRegistrationService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest
@Import(LoanRateEventQueryIntegrationTest.Config.class)
class LoanRateEventQueryIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
    }
    @TestConfiguration static class Config {
        @Bean @Primary Clock fixed(){return Clock.fixed(Instant.parse("2026-02-28T15:30:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @MockitoBean BudgetRecalculationPort budget;
    @Autowired LoanEventQueryPort events;
    @Autowired LoanRegistrationService registration;
    @Autowired JdbcTemplate jdbc;

    long owner() {
        var now=Timestamp.from(Instant.parse("2026-02-28T15:30:00Z"));
        return jdbc.queryForObject("INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,?,?) RETURNING id",Long.class,UUID.randomUUID().toString(),now,now);
    }
    long loan(long user)throws Exception{return registration.create(user,new LoanPreviewTest().body(),null).body().path("data").path("loan_id").longValue();}
    LocalDate day(int d){return LocalDate.of(2026,3,d);}
    DateRange range(int start,int end){return new DateRange(day(start),day(end));}
    void rate(long user,long loan,int d,String value) {
        jdbc.update("INSERT INTO loan_rate_histories(user_id,loan_id,effective_date,annual_rate,created_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP)",user,loan,day(d),new BigDecimal(value));
    }

    @Test void runtimePortExcludesRegistrationAndDoesNotInventChangesForEmptyOwners()throws Exception {
        long user=owner();loan(user);
        assertThat(events.rateChanges(user,range(1,31))).isEmpty();
        assertThat(events.rateChanges(owner(),range(1,31))).isEmpty();
        assertThatThrownBy(()->events.rateChanges(0,range(1,31))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void predecessorBeforeRangeAndBothInclusiveBoundariesUsePercentagePoints()throws Exception {
        long user=owner(),id=loan(user);
        rate(user,id,5,"7.125");rate(user,id,10,"6.875");rate(user,id,11,"9");
        var rows=events.rateChanges(user,range(5,10));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).eventDate()).isEqualTo(day(5));assertThat(rows.get(0).rateChangePp()).isEqualByComparingTo("1.125");
        assertThat(rows.get(1).eventDate()).isEqualTo(day(10));assertThat(rows.get(1).rateChangePp()).isEqualByComparingTo("-0.25");
        assertThat(rows).allSatisfy(e->{assertThat(e.loanId()).isEqualTo(id);assertThat(e.monthlyBurdenDelta()).isNull();});
    }
    @Test void effectiveDateThenIdDefinesSequenceAndZeroRateIsAnActualValue()throws Exception {
        long user=owner(),id=loan(user);
        rate(user,id,20,"8");rate(user,id,5,"0");rate(user,id,5,"7.125");rate(user,id,5,"7.125");
        var rows=events.rateChanges(user,range(5,20));
        assertThat(rows).extracting(LoanEventQueryPort.Event::eventDate).containsExactly(day(5),day(5),day(20));
        assertThat(rows.get(0).rateChangePp()).isEqualByComparingTo("-6");
        assertThat(rows.get(1).rateChangePp()).isEqualByComparingTo("7.125");
        assertThat(rows.get(2).rateChangePp()).isEqualByComparingTo("0.875");
        assertThatThrownBy(()->rows.clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void preservesPaidDeletedLoansAndSeparatesOwnersAndPerLoanPredecessors()throws Exception {
        long a=owner(),b=owner(),paid=loan(a),deleted=loan(a),other=loan(b);
        rate(a,paid,5,"7");rate(a,deleted,5,"5");rate(b,other,5,"10");
        jdbc.update("UPDATE loans SET status='PAID',remaining_principal=0 WHERE id=?",paid);
        jdbc.update("UPDATE loans SET status='DELETED',initial_plan_snapshot='{}'::jsonb WHERE id=?",deleted);
        var rows=events.rateChanges(a,range(5,5));
        assertThat(rows).extracting(LoanEventQueryPort.Event::loanId).containsExactly(paid,deleted);
        assertThat(rows.get(0).rateChangePp()).isEqualByComparingTo("1");assertThat(rows.get(1).rateChangePp()).isEqualByComparingTo("-1");
        assertThat(events.rateChanges(b,range(5,5))).singleElement().satisfies(e->assertThat(e.loanId()).isEqualTo(other));
    }
    @Test void repeatedReadsUseOnlyOriginalHistoryAndLeaveFinancialStateUntouched()throws Exception {
        long user=owner(),id=loan(user);rate(user,id,5,"7");
        jdbc.update("UPDATE loans SET annual_rate=19 WHERE id=?",id);
        var before=jdbc.queryForMap("SELECT * FROM loans WHERE id=?",id);
        var history=jdbc.queryForList("SELECT * FROM loan_rate_histories WHERE loan_id=? ORDER BY id",id);
        var revision=jdbc.queryForList("SELECT revision FROM demo_users WHERE id=?",user);
        assertThat(events.rateChanges(user,range(5,5))).isEqualTo(events.rateChanges(user,range(5,5)));
        assertThat(events.rateChanges(user,range(5,5)).getFirst().rateChangePp()).isEqualByComparingTo("1");
        assertThat(jdbc.queryForMap("SELECT * FROM loans WHERE id=?",id)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM loan_rate_histories WHERE loan_id=? ORDER BY id",id)).isEqualTo(history);
        assertThat(jdbc.queryForList("SELECT revision FROM demo_users WHERE id=?",user)).isEqualTo(revision);
    }
}
