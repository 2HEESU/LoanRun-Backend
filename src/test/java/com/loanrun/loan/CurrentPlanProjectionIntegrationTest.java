package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.service.*;
import com.loanrun.loan.service.CurrentLoanTermsQueryService.Reason;
import com.loanrun.payment.read.PaymentObservation.*;
import com.loanrun.payment.read.PaymentObservationSource;
import com.loanrun.payment.repository.PaymentObservationSnapshotRepository;
import com.loanrun.payment.service.PaymentObservationQueryService;
import com.loanrun.repaymentsaving.read.SavingObservationSource;
import com.loanrun.repaymentsaving.repository.SavingObservationSnapshotRepository;
import com.loanrun.repaymentsaving.service.SavingObservationQueryService;
import com.loanrun.repaymentplan.domain.PlanVersion.*;
import com.loanrun.repaymentplan.domain.PlanProjectionBasisPolicy.Source;
import com.loanrun.repaymentplan.repository.PlanVersionRepository;
import com.loanrun.repaymentplan.service.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest
@Import(CurrentPlanProjectionIntegrationTest.Config.class)
class CurrentPlanProjectionIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-03-01T00:00:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}
        public Clock withZone(ZoneId zone){return Clock.fixed(value,zone);}
        public Instant instant(){return value;}
    }
    @TestConfiguration static class Config {@Bean @Primary TestClock projectionClock(){return new TestClock();}}
    @MockitoBean BudgetRecalculationPort budget; // Registration fixture only; projection uses real sources/engine.
    @Autowired TestClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoanRegistrationService registration;
    @Autowired LoanSourceRepository sources;
    @Autowired CurrentLoanTermsQueryService terms;
    @Autowired PlanPayoffQueryService projections;
    @Autowired PlanSourceQueryService planSources;
    @Autowired PlanVersionRepository versions;
    @Autowired RevisionPort revisions;
    @Autowired TransactionTemplate tx;
    @Autowired RepaymentEngine engine;
    @Autowired PaymentObservationSnapshotRepository paymentSnapshots;
    @Autowired SavingObservationSnapshotRepository savingSnapshots;
    @BeforeEach void setup()throws Exception {
        clock.value=Instant.parse("2026-03-01T00:00:00Z");
        if(jdbc.queryForObject("SELECT to_regclass('repayment_plan_versions')",String.class)==null)
            jdbc.execute(Files.readString(Path.of("docs/sql/repayment_plan_versions_proposal.sql")));
    }
    long owner(){return jdbc.queryForObject("INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,?,?) RETURNING id",Long.class,
        UUID.randomUUID().toString(),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));}
    long loan(long owner,boolean bullet,boolean exempt)throws Exception {
        var body=new LoanPreviewTest().body();body.put("fee_exempt",exempt);
        if(bullet){body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);}
        return registration.create(owner,body,null).body().path("data").path("loan_id").longValue();
    }
    long payment(long user,long loan,long principal,long extra,long interest) {
        return jdbc.queryForObject("""
            INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at)
            VALUES (?,?,'2026-03-20','REGULAR',?,?,?,?,0,0,?) RETURNING id
            """,Long.class,user,loan,principal+extra+interest,principal,extra,interest,Timestamp.from(clock.instant()));
    }
    void allocate(long user,long loan,long payment,long principal,long interest) {
        jdbc.update("INSERT INTO payment_allocations(user_id,loan_id,payment_id,due_item_id,principal_amount,interest_amount,fee_amount) SELECT ?,?,?,id,?,?,0 FROM due_items WHERE loan_id=?",
            user,loan,payment,principal,interest,loan);
    }
    void april(){clock.value=Instant.parse("2026-04-01T00:00:00Z");}
    @Test void realV2GoalsAndCurrentTermsProduceAPayoffWithoutInventingObservedMonths()throws Exception {
        long user=owner(),id=loan(user,false,true);var result=projections.current(user,id);
        assertThat(result.reason()).isEqualTo(PlanPayoffQueryService.Reason.AVAILABLE);
        assertThat(result.plan().version()).isEqualTo(1);assertThat(result.plan().basisTerms()).isNull();
        assertThat(result.projection().basis().source()).isEqualTo(Source.FALLBACK_PLAN);
        assertThat(result.projection().basis().observedMonthCount()).isZero();
        assertThat(result.projection().simulation().projectedPayoffDate()).isEqualTo(LocalDate.of(2026,12,25));
        assertThat(result.projection().simulation().totalInterest()).isEqualTo(330000);
        assertThat(result.projection().simulation().totalFee()).isZero();
    }
    @Test void storedNewVersionTakesEffectFromTheNextModeledCycle()throws Exception {
        long user=owner(),id=loan(user,false,true);var current=terms.current(user,id).terms();
        tx.execute(s->versions.append(user,1,new Draft(id,Mode.EXTRA_AMOUNT,null,null,current.maturityDate(),300000,0,current.nextDueDate(),
            revisions.current(user),current,new Inputs(300000L,null,null),null)));
        var result=projections.current(user,id);assertThat(result.plan().version()).isEqualTo(2);
        assertThat(result.projection().simulation().projectedPayoffDate()).isEqualTo(LocalDate.of(2026,10,25));
        assertThat(sources.loan(user,id).initialPlan().version()).isEqualTo(1);
    }
    @Test void overdueUnrecordedOrConfirmedUnpaidCycleDoesNotBecomeAssumedPaid()throws Exception {
        long user=owner(),id=loan(user,false,true);april();
        for(boolean confirmed:new boolean[]{false,true}) {
            jdbc.update("UPDATE due_items SET confirmed_unpaid=? WHERE loan_id=?",confirmed,id);
            var result=projections.current(user,id);assertThat(result.reason()).isEqualTo(PlanPayoffQueryService.Reason.LOAN_BASIS_UNAVAILABLE);
            assertThat(result.current().reason()).isEqualTo(Reason.PAST_DUE_UNSETTLED);assertThat(result.projection()).isNull();
        }
    }
    @Test void partialUpcomingPaymentCannotBeReplayedAsAFullFuturePayment()throws Exception {
        long user=owner(),id=loan(user,false,true);clock.value=Instant.parse("2026-03-21T00:00:00Z");
        long payment=payment(user,id,0,0,50000);allocate(user,id,payment,0,50000);
        assertThat(terms.current(user,id).reason()).isEqualTo(Reason.PARTIALLY_SETTLED_DUE);
    }
    @Test void completedFirstCycleAdvancesWithoutReusingBankAmountOrSubtractingPrincipalTwice()throws Exception {
        long user=owner(),id=loan(user,false,true);long payment=payment(user,id,1200000,0,60000);allocate(user,id,payment,1200000,60000);
        jdbc.update("UPDATE loans SET remaining_principal=10800000,contractual_monthly_payment=1260000 WHERE id=?",id);april();
        var result=projections.current(user,id);var current=result.current().terms();
        assertThat(current.nextDueDate()).isEqualTo(LocalDate.of(2026,4,25));assertThat(current.contractualNextAmount()).isNull();
        assertThat(current.outstanding()).isEqualTo(10800000);assertThat(result.projection().simulation().totalPrincipal()).isEqualTo(10800000);
        assertThat(result.projection().simulation().totalInterest()).isEqualTo(270000);
    }
    @Test void confirmedActualExtraPrincipalUsesTheRealEngineOnTheCurrentMaterializedBalance()throws Exception {
        long user=owner(),id=loan(user,false,true);long payment=payment(user,id,1200000,200000,60000);allocate(user,id,payment,1200000,60000);
        jdbc.update("UPDATE loans SET remaining_principal=10600000 WHERE id=?",id);april();
        var scope=new Scope(user,id,YearMonth.of(2026,3));var snapshot=paymentSnapshots.snapshot(scope);
        var beans=new StaticListableBeanFactory();beans.addBean("evidence",(PaymentObservationSource)requested->requested.equals(scope)?Optional.of(
            new Confirmation(scope,snapshot.fingerprint(),Kind.RECORDING_COMPLETE,clock.instant().minusSeconds(1))):Optional.empty());
        var observed=new PlanObservationQueryService(sources,new PaymentObservationQueryService(paymentSnapshots,beans.getBeanProvider(PaymentObservationSource.class),clock),
            new SavingObservationQueryService(savingSnapshots,beans.getBeanProvider(SavingObservationSource.class),clock),clock);
        var result=new PlanPayoffQueryService(terms,planSources,observed,engine).current(user,id);
        assertThat(result.projection().basis().source()).isEqualTo(Source.ACTUAL_AVERAGE);
        assertThat(result.projection().basis().monthlyAmount()).isEqualTo(200000);assertThat(result.current().terms().outstanding()).isEqualTo(10600000);
        assertThat(result.projection().simulation().totalPrincipal()).isEqualTo(10600000);
        assertThat(result.projection().simulation().rows().getFirst().extraPrincipal()).isEqualTo(200000);
    }
    @Test void frozenInterestAfterAnActualExtraPaymentIsNotSilentlyRepriced()throws Exception {
        long user=owner(),id=loan(user,false,true);payment(user,id,0,200000,0);
        clock.value=Instant.parse("2026-03-21T00:00:00Z");
        jdbc.update("UPDATE loans SET remaining_principal=11800000 WHERE id=?",id);
        assertThat(terms.current(user,id).reason()).isEqualTo(Reason.UNSUPPORTED_FROZEN_DUE);
    }
    @Test void bulletUsesActualAssetsAndNeverClaimsThatSavingReducedInterest()throws Exception {
        long user=owner(),id=loan(user,true,true);
        jdbc.update("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,'2026-03-01',1000000,?)",user,id,Timestamp.from(clock.instant()));
        var result=projections.current(user,id);
        assertThat(result.current().terms().currentSavingBalance()).isEqualTo(3000000);
        assertThat(result.current().terms().outstanding()).isEqualTo(12000000);
        assertThat(result.projection().simulation().totalInterest()).isEqualTo(600000);
        assertThat(result.projection().simulation().projectedPayoffDate()).isEqualTo(LocalDate.of(2026,12,25));
    }
    @Test void unknownFeesRemainNullInRealSourceProjection()throws Exception {
        long user=owner(),id=loan(user,false,false);var simulation=projections.current(user,id).projection().simulation();
        assertThat(simulation.totalFee()).isNull();assertThat(simulation.totalCash()).isNull();assertThat(simulation.warnings()).contains("FEE_UNKNOWN");
    }
    @Test void preservedInactiveAndZeroBalanceLoansDoNotBecomeActivePayoffSimulations()throws Exception {
        long user=owner(),id=loan(user,false,true);
        jdbc.update("UPDATE loans SET remaining_principal=0,status='PAID' WHERE id=?",id);
        assertThat(projections.current(user,id).current().reason()).isEqualTo(Reason.INACTIVE_OR_ZERO);
        jdbc.update("UPDATE loans SET status='DELETED' WHERE id=?",id);
        assertThat(projections.current(user,id).projection()).isNull();
    }
    @Test void ownerScopeAndSelectedLoanParsingArePreserved()throws Exception {
        long user=owner(),other=owner(),id=loan(user,false,true),broken=loan(user,false,true);
        jdbc.update("UPDATE loans SET initial_plan_snapshot='{}'::jsonb WHERE id=?",broken);
        assertThat(projections.current(user,id).reason()).isEqualTo(PlanPayoffQueryService.Reason.AVAILABLE);
        for(long target:new long[]{id,Long.MAX_VALUE,0})assertThatThrownBy(()->projections.current(other,target))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("LOAN_NOT_FOUND"));
    }
    @Test void missingPastAnchorCannotBeReplacedByALaterFrozenDue()throws Exception {
        long user=owner(),id=loan(user,false,true);jdbc.update("UPDATE due_items SET due_date='2026-04-25' WHERE loan_id=?",id);april();
        assertThat(terms.current(user,id).reason()).isEqualTo(Reason.PAST_DUE_UNSETTLED);
    }
    @Test void extraFrozenFutureCyclesAreNotIgnoredByTheProjection()throws Exception {
        long user=owner(),id=loan(user,false,true);
        jdbc.update("""
            INSERT INTO due_items(user_id,loan_id,due_date,principal_amount,interest_amount,fee_amount,required_amount,confirmed_unpaid,terms_revision,created_at)
            VALUES (?,?,'2026-04-25',1200000,54000,0,1254000,false,1,?)
            """,user,id,Timestamp.from(clock.instant()));
        assertThat(terms.current(user,id).reason()).isEqualTo(Reason.UNSUPPORTED_FROZEN_DUE);
    }
    @Test void repeatedProjectionDoesNotChangeSourcesRevisionOrPlanHistory()throws Exception {
        long user=owner(),id=loan(user,false,true);var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));
        var account=jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",user);var history=versions.versions(user,id);
        assertThat(projections.current(user,id)).isEqualTo(projections.current(user,id));
        assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",user)).isEqualTo(account);assertThat(versions.versions(user,id)).isEqualTo(history);
    }
}
