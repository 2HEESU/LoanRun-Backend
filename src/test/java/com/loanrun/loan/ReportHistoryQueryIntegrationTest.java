package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.service.LoanRegistrationService;
import com.loanrun.payment.read.PaymentObservationSource;
import com.loanrun.payment.service.PaymentObservationQueryService;
import com.loanrun.repaymentsaving.read.SavingObservationSource;
import com.loanrun.repaymentsaving.service.SavingObservationQueryService;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
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
@Import(ReportHistoryQueryIntegrationTest.Config.class)
class ReportHistoryQueryIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-02-28T15:30:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}public Clock withZone(ZoneId z){return Clock.fixed(value,z);}public Instant instant(){return value;}
    }
    @TestConfiguration static class Config {@Bean @Primary TestClock historyClock(){return new TestClock();}}
    @MockitoBean BudgetRecalculationPort budgets;
    @MockitoBean PaymentObservationSource paymentProof;
    @MockitoBean SavingObservationSource savingProof;
    @Autowired ReportHistoryQueryPort report;
    @Autowired LoanQueryPort loans;
    @Autowired LoanRegistrationService registration;
    @Autowired PaymentObservationQueryService payments;
    @Autowired SavingObservationQueryService savings;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @BeforeEach void reset(){clock.value=Instant.parse("2026-02-28T15:30:00Z");}
    long user(){var now=Timestamp.from(clock.instant());return jdbc.queryForObject("INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,?,?) RETURNING id",Long.class,UUID.randomUUID().toString(),now,now);}
    long loan(long u,boolean bullet)throws Exception {
        var body=new LoanPreviewTest().body();if(bullet){body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);}
        return registration.create(u,body,null).body().path("data").path("loan_id").longValue();
    }
    DateRange march(){return new DateRange(LocalDate.of(2026,3,1),LocalDate.of(2026,3,31));}
    DateRange april(){return new DateRange(LocalDate.of(2026,4,1),LocalDate.of(2026,4,30));}
    void pay(long u,long id,long extra,String paid,String created){jdbc.update("INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at) VALUES (?,?,?,'EXTRA',?,0,?,0,0,0,?)",u,id,LocalDate.parse(paid),extra,extra,Timestamp.from(Instant.parse(created)));}
    void completePayments() {
        when(paymentProof.find(any())).thenAnswer(inv->{var scope=inv.<com.loanrun.payment.read.PaymentObservation.Scope>getArgument(0);var s=payments.snapshot(scope.userId(),scope.month(),scope.loanId());
            return Optional.of(new com.loanrun.payment.read.PaymentObservation.Confirmation(scope,s.fingerprint(),s.recorded().paymentCount()==0?com.loanrun.payment.read.PaymentObservation.Kind.ZERO_CONFIRMED:com.loanrun.payment.read.PaymentObservation.Kind.RECORDING_COMPLETE,clock.instant()));});
    }
    void completeSavings() {
        when(savingProof.find(any())).thenAnswer(inv->{var scope=inv.<com.loanrun.repaymentsaving.read.SavingObservation.Scope>getArgument(0);var s=savings.snapshot(scope.userId(),scope.loanId(),scope.month());
            return Optional.of(new com.loanrun.repaymentsaving.read.SavingObservation.Confirmation(scope,s.fingerprint(),s.recorded().count()==0?com.loanrun.repaymentsaving.read.SavingObservation.Kind.ZERO_CONFIRMED:com.loanrun.repaymentsaving.read.SavingObservation.Kind.RECORDING_COMPLETE,clock.instant()));});
    }
    @Test void selectedLoanIsIndependentlyScopedAndHouseholdMatchesTheLoanPort()throws Exception {
        long u=user(),a=loan(u,false),b=loan(u,false),v=user(),foreign=loan(v,false);
        pay(u,a,100,"2026-03-01","2026-03-01T00:00:00Z");pay(u,b,500,"2026-03-01","2026-03-01T00:00:00Z");pay(v,foreign,999,"2026-03-01","2026-03-01T00:00:00Z");completePayments();
        var single=report.history(u,a,march()).getFirst();var all=report.history(u,null,march()).getFirst();
        assertThat(single.extraPrincipal()).isEqualTo(100);assertThat(single.registeredBalance()).isEqualTo(12000000);
        assertThat(all.extraPrincipal()).isEqualTo(600);assertThat(all.registeredBalance()).isEqualTo(24000000);
        assertThat(report.history(u,null,march())).isEqualTo(loans.history(u,march()));
    }
    @Test void foreignMissingAndInvalidLoanIdsAre404EvenWhenNoEvidenceExists()throws Exception {
        long u=user(),v=user(),id=loan(u,false);
        for(long requested:List.of(id,Long.MAX_VALUE,0L))assertThatThrownBy(()->report.history(v,requested,march()))
            .isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status().value()).isEqualTo(404);assertThat(e.code()).isEqualTo("LOAN_NOT_FOUND");});
    }
    @Test void paidAndDeletedHistoryUsesPreservedFactsWithoutCurrentPlanOrBalanceSubstitution()throws Exception {
        long u=user(),a=loan(u,false),b=loan(u,false);pay(u,a,100,"2026-03-01","2026-03-01T00:00:00Z");pay(u,b,500,"2026-03-01","2026-03-01T00:00:00Z");
        jdbc.update("UPDATE loans SET status='PAID',remaining_principal=0 WHERE id=?",a);jdbc.update("UPDATE loans SET status='DELETED',remaining_principal=1,initial_plan_snapshot='{}'::jsonb WHERE id=?",b);
        completePayments();clock.value=Instant.parse("2026-03-31T15:00:00Z");
        assertThat(report.history(u,a,april()).getFirst().openingBalance()).isEqualTo(11999900);
        assertThat(report.history(u,b,april()).getFirst().openingBalance()).isEqualTo(11999500);
    }
    @Test void bulletHistoryNeedsIndependentSavingEvidenceAndNeverCountsInitialAssetsAsDeposits()throws Exception {
        long u=user(),id=loan(u,true);jdbc.update("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,'2026-03-01',700,?)",u,id,Timestamp.from(clock.instant()));
        completePayments();assertThat(report.history(u,id,march())).isEmpty();completeSavings();
        var row=report.history(u,id,march()).getFirst();assertThat(row.savingAdditions()).isEqualTo(700);assertThat(row.savingsUsed()).isZero();assertThat(row.observed()).isTrue();
    }
    @Test void completionDoesNotInferZeroFromMissingRecordsAndStaleEvidenceCannotGrantObservation()throws Exception {
        long u=user(),id=loan(u,false);assertThat(report.history(u,id,march())).isEmpty();
        var scope=new com.loanrun.payment.read.PaymentObservation.Scope(u,id,YearMonth.of(2026,3));var snapshot=payments.snapshot(u,scope.month(),id);
        when(paymentProof.find(any())).thenReturn(Optional.of(new com.loanrun.payment.read.PaymentObservation.Confirmation(scope,snapshot.fingerprint(),com.loanrun.payment.read.PaymentObservation.Kind.ZERO_CONFIRMED,clock.instant())));
        assertThat(report.history(u,id,march())).singleElement().satisfies(r->assertThat(r.extraPrincipal()).isZero());
        pay(u,id,100,"2026-03-01","2026-03-01T00:00:00Z");assertThat(report.history(u,id,march())).isEmpty();
    }
    @Test void correctionDayOrderMustBeProvenAndAnObservedWindowDoesNotReplaceABalanceBasis()throws Exception {
        long u=user(),id=loan(u,false);jdbc.update("INSERT INTO loan_balance_snapshots(user_id,loan_id,effective_date,amount,reason,created_at) VALUES (?,?,'2026-03-15',10000000,'CORRECTION','2026-03-15T00:00:00Z')",u,id);
        pay(u,id,100,"2026-03-15","2026-03-15T00:00:00Z");completePayments();clock.value=Instant.parse("2026-03-31T15:00:00Z");
        assertThatThrownBy(()->report.history(u,id,april())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        jdbc.update("UPDATE payments SET created_at='2026-03-15T00:00:01Z' WHERE loan_id=?",id);
        assertThat(report.history(u,id,april()).getFirst().openingBalance()).isEqualTo(9999900);
        jdbc.update("UPDATE payments SET created_at='2026-03-14T23:59:59Z' WHERE loan_id=?",id);
        assertThat(report.history(u,id,april()).getFirst().openingBalance()).isEqualTo(10000000);
    }
}
