package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.BudgetRecalculationPort;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.service.LoanRegistrationService;
import com.loanrun.payment.read.PaymentObservation;
import com.loanrun.payment.read.PaymentObservationSource;
import com.loanrun.payment.repository.PaymentObservationSnapshotRepository;
import com.loanrun.payment.service.PaymentObservationQueryService;
import com.loanrun.repaymentsaving.read.SavingObservation.*;
import com.loanrun.repaymentsaving.read.SavingObservationSource;
import com.loanrun.repaymentsaving.repository.SavingObservationSnapshotRepository;
import com.loanrun.repaymentsaving.service.SavingObservationQueryService;
import com.loanrun.repaymentplan.domain.PlanProjectionBasisPolicy;
import com.loanrun.repaymentplan.domain.PlanProjectionBasisPolicy.Metric;
import com.loanrun.repaymentplan.service.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(properties="spring.flyway.target=3.1")
@Import(PlanObservationQueryIntegrationTest.Config.class)
class PlanObservationQueryIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-03-01T00:00:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}
        public Clock withZone(ZoneId zone){return Clock.fixed(value,zone);}
        public Instant instant(){return value;}
    }
    @TestConfiguration static class Config {@Bean @Primary TestClock observationClock(){return new TestClock();}}
    @MockitoBean BudgetRecalculationPort budgets; // Only creates registration fixtures. Observation reads use real repositories.
    @Autowired TestClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoanRegistrationService registration;
    @Autowired LoanSourceRepository loans;
    @Autowired PlanObservationQueryService runtime;
    @Autowired SavingObservationQueryService runtimeSavings;
    @Autowired SavingObservationSnapshotRepository savingSnapshots;
    @Autowired PaymentObservationSnapshotRepository paymentSnapshots;
    @Autowired PlanSourceQueryService plans;
    @Autowired PlanPayoffQueryService payoffQueries;
    @Autowired PlanPerformanceQueryService performanceQueries;
    @BeforeEach void reset(){clock.value=Instant.parse("2026-03-01T00:00:00Z");}
    void now(String instant){clock.value=Instant.parse(instant);}
    long owner(){return jdbc.queryForObject("INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,?,?) RETURNING id",Long.class,
        UUID.randomUUID().toString(),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));}
    long loan(long owner,boolean bullet)throws Exception {
        var body=new LoanPreviewTest().body();
        if(bullet){body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);}
        return registration.create(owner,body,null).body().path("data").path("loan_id").longValue();
    }
    long saving(long user,long loan,String date,long amount){return jdbc.queryForObject("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,?,?,?) RETURNING id",Long.class,
        user,loan,LocalDate.parse(date),amount,Timestamp.from(clock.instant()));}
    void payment(long user,long loan,String date,long principal,long extra,long fee,long used){jdbc.update("""
        INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at)
        VALUES (?,?,?,'REGULAR',?,?,?,0,?,?,?)
        """,user,loan,LocalDate.parse(date),principal+extra+fee,principal,extra,fee,used,Timestamp.from(clock.instant()));}
    Snapshot snapshot(long user,long loan,int month){return savingSnapshots.snapshot(new Scope(user,loan,YearMonth.of(2026,month)));}
    Confirmation confirm(Snapshot s,Kind kind){return new Confirmation(s.scope(),s.fingerprint(),kind,clock.instant().minusSeconds(1));}
    PlanObservationQueryService service(SavingObservationSource saving,PaymentObservationSource payment) {
        var beans=new StaticListableBeanFactory();if(saving!=null)beans.addBean("savingEvidence",saving);if(payment!=null)beans.addBean("paymentEvidence",payment);
        return new PlanObservationQueryService(loans,new PaymentObservationQueryService(paymentSnapshots,beans.getBeanProvider(PaymentObservationSource.class),clock),
            new SavingObservationQueryService(savingSnapshots,beans.getBeanProvider(SavingObservationSource.class),clock),clock);
    }
    @Test void realRuntimeKeepsPositiveAndEmptyMonthsUnknownWithoutACompletionProvider()throws Exception {
        long user=owner(),id=loan(user,true);saving(user,id,"2026-10-10",100);now("2026-11-20T00:00:00Z");
        var raw=runtimeSavings.observe(user,id,YearMonth.of(2026,10));
        assertThat(raw.source().recorded().additions()).isEqualTo(100);assertThat(raw.observed()).isFalse();assertThat(raw.actuals()).isNull();
        var window=runtime.current(user,id);assertThat(window.observations()).hasSize(6).allSatisfy(o->{assertThat(o.observed()).isFalse();assertThat(o.amount()).isNull();});
        assertThat(window.observations()).extracting(o->o.month().getMonthValue()).containsExactly(5,6,7,8,9,10);
    }
    @Test void savingCompletionAndConfirmedZeroFeedTheRealPlanBasisPolicyWithoutIncludingInitialAssets()throws Exception {
        long user=owner(),id=loan(user,true);saving(user,id,"2026-05-10",200);now("2026-11-20T00:00:00Z");
        var may=snapshot(user,id,5);var june=snapshot(user,id,6);
        var proofs=Map.of(may.scope(),confirm(may,Kind.RECORDING_COMPLETE),june.scope(),confirm(june,Kind.ZERO_CONFIRMED));
        var window=service(scope->Optional.ofNullable(proofs.get(scope)),null).current(user,id);
        var basis=PlanProjectionBasisPolicy.select(user,id,window.method(),window.registeredOn(),window.asOf(),plans.registration(user,id),window.observations());
        assertThat(basis.monthlyAmount()).isEqualTo(100);assertThat(basis.observedMonthCount()).isEqualTo(2);
        assertThat(basis.source()).isEqualTo(PlanProjectionBasisPolicy.Source.ACTUAL_AVERAGE);
        assertThat(window.observations()).allSatisfy(o->assertThat(o.metric()).isEqualTo(Metric.SAVING_ADDITION));
    }
    @Test void installmentUsesOnlyConfirmedExtraPrincipalAndDoesNotIncludeRegularPrincipalOrFees()throws Exception {
        long user=owner(),id=loan(user,false);payment(user,id,"2026-05-10",500,200,10,0);now("2026-11-20T00:00:00Z");
        var scope=new PaymentObservation.Scope(user,id,YearMonth.of(2026,5));var s=paymentSnapshots.snapshot(scope);
        PaymentObservationSource proofs=request->request.equals(scope)?Optional.of(new PaymentObservation.Confirmation(scope,s.fingerprint(),PaymentObservation.Kind.RECORDING_COMPLETE,clock.instant().minusSeconds(1))):Optional.empty();
        var window=service(request->{throw new AssertionError("No saving evidence for installment");},proofs).current(user,id);
        assertThat(window.observations().getFirst().amount()).isEqualTo(200);assertThat(window.observations().getFirst().metric()).isEqualTo(Metric.EXTRA_PRINCIPAL);
    }
    @Test void paymentCompletionCannotConfirmBulletSavingPerformance()throws Exception {
        long user=owner(),id=loan(user,true);saving(user,id,"2026-05-10",200);now("2026-11-20T00:00:00Z");
        var window=service(null,scope->{throw new AssertionError("BULLET uses saving evidence");}).current(user,id);
        assertThat(window.observations()).allSatisfy(o->assertThat(o.observed()).isFalse());
    }
    @Test void registrationMonthAndCurrentMonthAreHandledWithoutPullingOlderMonthsToFillSixSlots()throws Exception {
        now("2026-06-15T00:00:00Z");long user=owner(),id=loan(user,true);now("2026-08-20T00:00:00Z");
        assertThat(runtime.current(user,id).observations()).extracting(o->o.month().getMonthValue()).containsExactly(6,7);
        long current=loan(user,true);assertThat(runtime.current(user,current).observations()).isEmpty();
    }
    @Test void kstMonthRolloverAddsTheNewlyCompletedMonth()throws Exception {
        long user=owner(),id=loan(user,true);now("2026-10-31T14:59:59Z");
        assertThat(runtime.current(user,id).observations().getLast().month()).isEqualTo(YearMonth.of(2026,9));
        now("2026-10-31T15:00:00Z");var window=runtime.current(user,id);
        assertThat(window.asOf()).isEqualTo(LocalDate.of(2026,11,1));assertThat(window.observations().getLast().month()).isEqualTo(YearMonth.of(2026,10));
    }
    @Test void foreignAndMissingLoanNeverLeakSourceTotalsOrProofs()throws Exception {
        long a=owner(),b=owner(),id=loan(a,true);now("2026-11-20T00:00:00Z");
        for(long target:new long[]{id,Long.MAX_VALUE}) {
            assertThatThrownBy(()->runtime.current(b,target)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("LOAN_NOT_FOUND"));
            assertThatThrownBy(()->snapshot(b,target,5)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("LOAN_NOT_FOUND"));
        }
    }
    @Test void savingFingerprintChangesWithFinancialRowsButNotMemoOtherMonthsInitialAssetsOrWithdrawals()throws Exception {
        long user=owner(),id=loan(user,true),row=saving(user,id,"2026-05-10",100);var first=snapshot(user,id,5);
        jdbc.update("UPDATE repayment_savings SET memo='가상 메모' WHERE id=?",row);saving(user,id,"2026-06-10",999);
        payment(user,id,"2026-05-11",50,0,0,50);jdbc.update("UPDATE loans SET initial_savings_balance=3000000 WHERE id=?",id);
        assertThat(snapshot(user,id,5)).isEqualTo(first);
        now("2026-11-20T00:00:00Z");var proof=confirm(first,Kind.RECORDING_COMPLETE);
        jdbc.update("UPDATE repayment_savings SET amount=101 WHERE id=?",row);
        var result=service(scope->scope.equals(proof.scope())?Optional.of(proof):Optional.empty(),null).current(user,id).observations().getFirst();
        assertThat(result.observed()).isFalse();assertThat(result.amount()).isNull();
    }
    @Test void replacingSameAmountOrMovingItsDateInvalidatesTheOldFingerprint()throws Exception {
        long user=owner(),id=loan(user,true),row=saving(user,id,"2026-05-10",100);var first=snapshot(user,id,5);
        jdbc.update("DELETE FROM repayment_savings WHERE id=?",row);long replacement=saving(user,id,"2026-05-10",100);
        var replaced=snapshot(user,id,5);assertThat(replaced.recorded()).isEqualTo(first.recorded());assertThat(replaced.fingerprint()).isNotEqualTo(first.fingerprint());
        jdbc.update("UPDATE repayment_savings SET saved_at='2026-06-10' WHERE id=?",replacement);
        assertThat(snapshot(user,id,5).recorded().additions()).isZero();assertThat(snapshot(user,id,6).recorded().additions()).isEqualTo(100);
    }
    @Test void sameDateAdditionsRetainBothRowsAndConfirmedZeroCannotOverrideThem()throws Exception {
        long user=owner(),id=loan(user,true);saving(user,id,"2026-05-10",100);saving(user,id,"2026-05-10",100);now("2026-11-20T00:00:00Z");
        var s=snapshot(user,id,5);assertThat(s.recorded()).isEqualTo(new Recorded(2,200));
        var result=service(scope->scope.equals(s.scope())?Optional.of(confirm(s,Kind.ZERO_CONFIRMED)):Optional.empty(),null).current(user,id);
        assertThat(result.observations().getFirst().amount()).isNull();
    }
    @Test void brokenSavingEvidenceIsAnErrorNotASuccessfulFallback()throws Exception {
        long user=owner(),id=loan(user,true);now("2026-11-20T00:00:00Z");var s=snapshot(user,id,5);
        for(boolean wrong:new boolean[]{true,false}) {
            var service=service(scope->wrong?Optional.of(new Confirmation(new Scope(user+100,id,scope.month()),s.fingerprint(),Kind.ZERO_CONFIRMED,clock.instant())):null,null);
            assertThatThrownBy(()->service.current(user,id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        }
    }
    @Test void preservedInactiveLoanEvidenceDoesNotBecomeAnActivePublicProjection()throws Exception {
        long user=owner(),id=loan(user,true);now("2026-11-20T00:00:00Z");jdbc.update("UPDATE loans SET status='DELETED' WHERE id=?",id);
        assertThat(runtime.current(user,id).observations()).hasSize(6);assertThat(snapshot(user,id,5).recorded().count()).isZero();
    }
    @Test void repeatedReadsKeepRevisionFinancialSourcesAndTimestampsUnchanged()throws Exception {
        long user=owner(),id=loan(user,true);saving(user,id,"2026-05-10",100);now("2026-11-20T00:00:00Z");
        var before=loans.loanSnapshot(user,id,LocalDate.of(2026,11,20));var account=jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",user);
        assertThat(runtime.current(user,id)).isEqualTo(runtime.current(user,id));
        assertThat(loans.loanSnapshot(user,id,LocalDate.of(2026,11,20))).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",user)).isEqualTo(account);
    }
    @Test void sumOverflowAndNonBulletSavingObservationAreNotSilentlyAccepted()throws Exception {
        long user=owner(),id=loan(user,true),installment=loan(user,false);saving(user,id,"2026-05-10",Long.MAX_VALUE);saving(user,id,"2026-05-11",1);
        assertThatThrownBy(()->snapshot(user,id,5)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(()->snapshot(user,installment,5)).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
            .hasCauseInstanceOf(IllegalArgumentException.class);
        now("2026-11-20T00:00:00Z");
        assertThatThrownBy(()->runtime.current(user,id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
    @Test void oneCapturedInstantKeepsTheWindowStableWhenEvidenceArrivesAcrossMidnight()throws Exception {
        long user=owner(),id=loan(user,true);now("2026-10-31T14:59:59Z");var captured=clock.instant();
        var result=service(scope->{
            clock.value=clock.value.plusSeconds(2);
            var s=savingSnapshots.snapshot(scope);
            return Optional.of(new Confirmation(scope,s.fingerprint(),Kind.ZERO_CONFIRMED,clock.instant()));
        },null).current(user,id);
        assertThat(result.knownAt()).isEqualTo(captured);assertThat(result.asOf()).isEqualTo(LocalDate.of(2026,10,31));
        assertThat(result.observations()).extracting(o->o.month().getMonthValue()).containsExactly(4,5,6,7,8,9);
        assertThat(result.observations()).allSatisfy(o->{assertThat(o.observed()).isFalse();assertThat(o.amount()).isNull();});
    }
    @Test void actualMissingVersionTableIsAnErrorInsteadOfAClaimThatOnlyV2Exists()throws Exception {
        long user=owner(),id=loan(user,false);
        assertThat(jdbc.queryForObject("SELECT to_regclass('repayment_plan_versions')",String.class)).isNull();
        assertThatThrownBy(()->payoffQueries.current(user,id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        assertThatThrownBy(()->performanceQueries.current(user,id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
}
