package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.service.LoanRegistrationService;
import com.loanrun.payment.read.PaymentObservation;
import com.loanrun.payment.read.PaymentObservationSource;
import com.loanrun.payment.service.PaymentObservationQueryService;
import com.loanrun.repaymentsaving.read.SavingObservation;
import com.loanrun.repaymentsaving.read.SavingObservationSource;
import com.loanrun.repaymentsaving.service.SavingObservationQueryService;
import com.loanrun.repaymentplan.domain.PlanVersion.*;
import com.loanrun.repaymentplan.repository.PlanVersionRepository;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "loanrun.demo-mode=true","server.servlet.session.cookie.secure=false","loanrun.reports.worker-enabled=false"})
@Import(PlanPerformancePortIntegrationTest.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PlanPerformancePortIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-03-01T01:00:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}
        public Clock withZone(ZoneId z){return Clock.fixed(value,z);}
        public Instant instant(){return value;}
    }
    @TestConfiguration static class Config {@Bean @Primary TestClock perfClock(){return new TestClock();}}

    @LocalServerPort int port;
    @MockitoBean PaymentObservationSource paymentProof;
    @MockitoBean SavingObservationSource savingProof;
    @Autowired PlanPerformanceQueryPort performance;
    @Autowired LoanQueryPort loans;
    @Autowired LoanRegistrationService registration;
    @Autowired PlanVersionRepository versions;
    @Autowired RevisionPort revisions;
    @Autowired PaymentObservationQueryService payments;
    @Autowired SavingObservationQueryService savings;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TestClock clock;

    @BeforeEach void reset() {
        clock.value=Instant.parse("2026-03-01T01:00:00Z");
        org.mockito.Mockito.reset(paymentProof,savingProof);
        org.mockito.Mockito.doReturn(Optional.empty()).when(paymentProof).find(any());
        org.mockito.Mockito.doReturn(Optional.empty()).when(savingProof).find(any());
    }

    long user() {
        var now=Timestamp.from(clock.instant());
        return jdbc.queryForObject("INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,?,?) RETURNING id",
            Long.class,UUID.randomUUID().toString(),now,now);
    }

    long loan(long u,boolean bullet)throws Exception {
        var body=new LoanPreviewTest().body();
        body.put("fee_exempt",true);
        body.put("start_date","2026-03-01");
        body.put("maturity_date","2027-03-25");
        body.put("payment_day",25);
        if(bullet){body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);}
        return registration.create(u,body,null).body().path("data").path("loan_id").longValue();
    }

    void savePlan(long u,long id,LocalDate effective,long extra,long saving) {
        var t=loans.terms(u,id,LocalDate.now(clock));
        var mode=saving>0?Mode.MONTHLY_AMOUNT:Mode.EXTRA_AMOUNT;
        var draft=new Draft(id,mode,null,null,t.maturityDate(),extra,saving,effective,revisions.current(u),t,new Inputs(extra>0?extra:null,saving>0?saving:null,null),null);
        tx.execute(s->versions.append(u,1,draft));
    }

    void extraPayment(long u,long id,LocalDate paidAt,long amount) {
        jdbc.update("INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at) VALUES (?,?,?,'EXTRA',?,0,?,0,0,0,?)",
            u,id,paidAt,amount,amount,Timestamp.from(clock.instant()));
    }

    void savingDeposit(long u,long id,LocalDate savedAt,long amount) {
        jdbc.update("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,?,?,?)",
            u,id,savedAt,amount,Timestamp.from(clock.instant()));
    }

    long dueItem(long u,long id,LocalDate dueDate,long required,boolean unpaidConfirmed) {
        return jdbc.queryForObject("""
            INSERT INTO due_items(user_id,loan_id,due_date,principal_amount,interest_amount,fee_amount,required_amount,confirmed_unpaid,terms_revision,created_at)
            VALUES (?,?,?,?,0,0,?,?,1,?) RETURNING id
            """,Long.class,u,id,dueDate,required,required,unpaidConfirmed,Timestamp.from(clock.instant()));
    }

    void payDue(long u,long id,long dueId,LocalDate paidAt,long amount) {
        long p=jdbc.queryForObject("INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at) VALUES (?,?,?,'REGULAR',?,?,0,0,0,0,?) RETURNING id",
            Long.class,u,id,paidAt,amount,amount,Timestamp.from(clock.instant()));
        jdbc.update("INSERT INTO payment_allocations(user_id,loan_id,payment_id,due_item_id,principal_amount,interest_amount,fee_amount) VALUES (?,?,?,?,?,0,0)",u,id,p,dueId,amount);
    }

    void observeAllMonths(Set<YearMonth> months) {
        org.mockito.Mockito.doAnswer(inv->{
            var scope=inv.<PaymentObservation.Scope>getArgument(0);
            if(!months.contains(scope.month()))return Optional.empty();
            var p=payments.snapshot(scope.userId(),scope.month(),scope.loanId());
            var kind=p.recorded().paymentCount()==0?PaymentObservation.Kind.ZERO_CONFIRMED:PaymentObservation.Kind.RECORDING_COMPLETE;
            return Optional.of(new PaymentObservation.Confirmation(scope,p.fingerprint(),kind,clock.instant()));
        }).when(paymentProof).find(any());
        org.mockito.Mockito.doAnswer(inv->{
            var scope=inv.<SavingObservation.Scope>getArgument(0);
            if(!months.contains(scope.month()))return Optional.empty();
            var s=savings.snapshot(scope.userId(),scope.loanId(),scope.month());
            var kind=s.recorded().count()==0?SavingObservation.Kind.ZERO_CONFIRMED:SavingObservation.Kind.RECORDING_COMPLETE;
            return Optional.of(new SavingObservation.Confirmation(scope,s.fingerprint(),kind,clock.instant()));
        }).when(savingProof).find(any());
    }

    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient c,String method,String path,String body)throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
            .header("Origin","http://localhost:5173").header("Content-Type","application/json")
            .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test void newOrUnobservedUserReturnsAllNullAndAllowsRealReportAdmission()throws Exception {
        var c=browser();
        long u=mapper.readTree(call(c,"GET","/api/users/me/",null).body()).path("data").path("user_id").asLong();
        assertThat(call(c,"PATCH","/api/settings/subscription/","{\"plan_type\":\"PRO\"}").statusCode()).isEqualTo(200);
        assertThat(call(c,"PUT","/api/budgets/profile/",
            "{\"income_type\":\"REGULAR\",\"monthly_income\":5000000,\"payday\":25,\"fixed_essential_expenses\":1000000,\"variable_essential_expenses\":500000,\"discretionary_expenses\":500000,\"savings\":200000,\"emergency_fund\":1000000}")
            .statusCode()).isEqualTo(200);
        var loanBody=new LoanPreviewTest().body();loanBody.put("fee_exempt",true);loanBody.put("start_date","2026-03-01");loanBody.put("maturity_date","2027-03-25");loanBody.put("payment_day",25);
        assertThat(call(c,"POST","/api/loans/",mapper.writeValueAsString(loanBody)).statusCode()).isEqualTo(201);

        var perf=performance.performance(u,LocalDate.of(2026,3,1));
        assertThat(perf).isEqualTo(new PlanPerformanceQueryPort.Performance(null,null,null));

        // Real POST /api/reports/ now uses the real PlanPerformanceQueryPort bean without 503.
        var report=call(c,"POST","/api/reports/","{}");
        assertThat(report.statusCode()).as(report.body()).isEqualTo(202);
        assertThat(mapper.readTree(report.body()).path("code").asText()).isEqualTo("REPORT_GENERATE_ACCEPTED");
    }

    @Test void threeMonthAdherenceCapsPerLoanOverpaymentAndHonorsMonthlyOverride()throws Exception {
        long u=user();
        long inst=loan(u,false);
        long bullet=loan(u,true);
        savePlan(u,inst,LocalDate.of(2026,3,25),200000,0);
        savePlan(u,bullet,LocalDate.of(2026,3,25),0,100000);

        // Move to June 10 so Mar/Apr/May are the 3 completed months.
        clock.value=Instant.parse("2026-06-10T01:00:00Z");

        // March: inst paid 300k (over 200k target -> capped at 200k), bullet saved 50k (target 100k -> 50k) => 250k / 300k
        extraPayment(u,inst,LocalDate.of(2026,3,25),300000);
        savingDeposit(u,bullet,LocalDate.of(2026,3,25),50000);

        // April: inst target overridden to 100k via monthly_allocations; paid 100k, bullet saved 100k => 200k / 200k (met!)
        long aprilBudget=jdbc.queryForObject("""
            INSERT INTO monthly_budgets(user_id,year_month,income_type,expected_income,payday,fixed_essential_expenses,variable_essential_expenses,
                discretionary_expenses,discretionary_limit,savings,reserve_amount,source,settlement_status,allocation_overridden,created_at,updated_at)
            VALUES (?,'2026-04-01','REGULAR',5000000,25,1000000,500000,500000,500000,200000,0,'INPUT','OPEN',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id
            """,Long.class,u);
        jdbc.update("INSERT INTO monthly_allocations(budget_id,loan_id,allocation_type,amount) VALUES (?,?,'EXTRA_PAYMENT',100000)",aprilBudget,inst);
        extraPayment(u,inst,LocalDate.of(2026,4,25),100000);
        savingDeposit(u,bullet,LocalDate.of(2026,4,25),100000);

        // May: inst paid 200k (target 200k), bullet saved 100k (target 100k) => 300k / 300k (met!)
        extraPayment(u,inst,LocalDate.of(2026,5,25),200000);
        savingDeposit(u,bullet,LocalDate.of(2026,5,25),100000);

        observeAllMonths(Set.of(YearMonth.of(2026,3),YearMonth.of(2026,4),YearMonth.of(2026,5)));

        // Adherence = (250k + 200k + 300k) / (300k + 200k + 300k) = 750 / 800 = 93.75%
        // Consecutive on track from May backwards: May (met), April (met), March (bullet missed) => 2
        var result=performance.performance(u,LocalDate.of(2026,6,10));
        assertThat(result.adherenceRate3m()).isEqualByComparingTo(new BigDecimal("93.75"));
        assertThat(result.consecutiveExtraOnTrackMonths()).isEqualTo(2);
    }

    @Test void streakDistinguishesConfirmedZeroFromMissingObservation()throws Exception {
        long u=user();
        long inst=loan(u,false);
        savePlan(u,inst,LocalDate.of(2026,3,25),200000,0);
        clock.value=Instant.parse("2026-06-10T01:00:00Z");

        // Unobserved May -> null streak (not fabricated 0) and null 3m adherence.
        observeAllMonths(Set.of(YearMonth.of(2026,3),YearMonth.of(2026,4)));
        var unobserved=performance.performance(u,LocalDate.of(2026,6,10));
        assertThat(unobserved.adherenceRate3m()).isNull();
        assertThat(unobserved.consecutiveExtraOnTrackMonths()).isNull();

        // Confirmed zero payment in May (below 200k target) -> confirmed 0 streak.
        observeAllMonths(Set.of(YearMonth.of(2026,5)));
        var confirmedMiss=performance.performance(u,LocalDate.of(2026,6,10));
        assertThat(confirmedMiss.adherenceRate3m()).isNull();
        assertThat(confirmedMiss.consecutiveExtraOnTrackMonths()).isZero();
    }

    @Test void missedPaymentCountExcludesUnrecordedPreservesPaidHistoryAndIsolatesOwners()throws Exception {
        long u=user(),other=user();
        long id=loan(u,false),otherId=loan(other,false);
        clock.value=Instant.parse("2026-06-10T01:00:00Z");

        // Only unrecorded due -> unknown (null), never automatically missed or fabricated 0.
        var initial=performance.performance(u,LocalDate.of(2026,6,10));
        assertThat(initial.missedPaymentCount6m()).isNull();

        // Add other user's confirmed unpaid due -> must not leak into user u.
        dueItem(other,otherId,LocalDate.of(2026,4,25),100000,true);
        assertThat(performance.performance(u,LocalDate.of(2026,6,10)).missedPaymentCount6m()).isNull();

        // User u pays April due while March due stays UNRECORDED -> confirmed 0 missed (unrecorded excluded).
        long aprilDue=dueItem(u,id,LocalDate.of(2026,4,25),100000,false);
        payDue(u,id,aprilDue,LocalDate.of(2026,4,25),100000);
        assertThat(performance.performance(u,LocalDate.of(2026,6,10)).missedPaymentCount6m()).isZero();

        // User u confirms May due as UNPAID, then marks loan PAID -> historical confirmed unpaid stays 1.
        dueItem(u,id,LocalDate.of(2026,5,25),100000,true);
        jdbc.update("UPDATE loans SET status='PAID', remaining_principal=0 WHERE id=?",id);
        assertThat(performance.performance(u,LocalDate.of(2026,6,10)).missedPaymentCount6m()).isEqualTo(1);

        // Future asOf rejected with 503.
        assertThatThrownBy(()->performance.performance(u,LocalDate.of(2026,6,11)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
}
