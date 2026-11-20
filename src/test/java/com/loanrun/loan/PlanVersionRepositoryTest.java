package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.service.LoanRegistrationService;
import com.loanrun.repaymentplan.domain.PlanVersion;
import com.loanrun.repaymentplan.domain.PlanVersion.*;
import com.loanrun.repaymentplan.repository.PlanVersionRepository;
import com.loanrun.repaymentplan.service.PlanSourceQueryService;
import com.loanrun.repaymentplan.domain.SavedPlanSource.Origin;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest
@Import(PlanVersionRepositoryTest.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PlanVersionRepositoryTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-02-28T15:30:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}
        public Clock withZone(ZoneId zone){return Clock.fixed(value,zone);}
        public Instant instant(){return value;}
    }
    @TestConfiguration static class Config {@Bean @Primary TestClock fixed(){return new TestClock();}}
    @MockitoBean BudgetRecalculationPort budget; // Loan fixture only, never a production provider.
    @Autowired TestClock clock;
    @Autowired PlanSourceQueryService queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired PlanVersionRepository plans;
    @Autowired LoanRegistrationService registration;
    @Autowired LoanSourceRepository sources;
    @Autowired RevisionPort revisions;
    @Autowired ObjectProvider<PlanQueryPort> publicPlans;
    @BeforeEach void proposal()throws Exception {
        clock.value=Instant.parse("2026-02-28T15:30:00Z");
        if(jdbc.queryForObject("SELECT to_regclass('repayment_plan_versions')",String.class)==null)
            jdbc.execute(Files.readString(Path.of("docs/sql/repayment_plan_versions_proposal.sql")));
    }
    long user(){return jdbc.queryForObject("INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,?,?) RETURNING id",Long.class,
        UUID.randomUUID().toString(),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));}
    long loan(long user,boolean bullet)throws Exception {
        var body=new LoanPreviewTest().body();if(bullet){body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);}
        return registration.create(user,body,null).body().path("data").path("loan_id").longValue();
    }
    LoanTerms terms(long user,long id) {
        var l=sources.loan(user,id);
        return new LoanTerms(id,user,l.productName(),l.loanType(),l.originalPrincipal(),l.storedRemainingPrincipal(),l.latestSnapshotDate(),l.annualRate(),l.rateType(),
            l.method(),l.graceEnd(),l.postGraceMethod(),l.storedNextDueDate(),l.paymentDay(),l.maturityDate(),l.contractualNextAmount(),l.baseAnchor(),l.fixedPrincipal(),
            l.prepaymentPolicy(),l.originalFeeMethod(),l.feeRate(),l.feeStart(),l.feeEnd(),l.initialSavings(),l.initialSavings(),l.storedStatus(),l.nextRateResetDate());
    }
    Draft draft(long user,long loan,long extra,LocalDate effective) {
        var t=terms(user,loan);return new Draft(loan,Mode.EXTRA_AMOUNT,null,null,t.maturityDate(),extra,0,effective,revisions.current(user),t,new Inputs(extra,null,null),null);
    }
    PlanVersion append(long user,long expected,Draft d){return tx.execute(s->plans.append(user,expected,d));}
    LocalDate date(int month){return LocalDate.of(2026,month,25);}
    @Test void storesSecondVersionWithoutChangingV2BaselineLoanSourcesOrUserRevision()throws Exception {
        long u=user(),l=loan(u,false);var before=sources.loanSnapshot(u,l,date(3));var account=jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",u);
        var d=draft(u,l,100000,date(3));var saved=append(u,1,d);
        assertThat(saved.version()).isEqualTo(2);assertThat(saved.draft()).isEqualTo(d);assertThat(saved.createdAt()).isEqualTo(clock.instant());
        assertThat(plans.versions(u,l)).containsExactly(saved);assertThat(sources.loanSnapshot(u,l,date(3))).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",u)).isEqualTo(account);
        assertThat(sources.loan(u,l).initialPlan().version()).isEqualTo(1);
    }
    @Test void effectiveDateIsDistinctFromLatestVersionAndSameDateUsesNewerVersion()throws Exception {
        long u=user(),l=loan(u,false);var a=append(u,1,draft(u,l,100,date(4)));var b=append(u,2,draft(u,l,200,date(5)));
        assertThat(plans.effectiveOn(u,l,date(3))).isEmpty();assertThat(plans.effectiveOn(u,l,date(4))).contains(a);
        assertThat(plans.effectiveOn(u,l,date(5))).contains(b);var c=append(u,3,draft(u,l,300,date(5)));
        assertThat(plans.effectiveOn(u,l,date(5))).contains(c);assertThat(plans.versions(u,l)).containsExactly(a,b,c);
    }
    @Test void laterCommitWithEarlierEffectivitySupersedesAnOlderQueuedVersion()throws Exception {
        long u=user(),l=loan(u,false);append(u,1,draft(u,l,100,date(6)));var newer=append(u,2,draft(u,l,200,date(5)));
        assertThat(plans.effectiveOn(u,l,date(6))).contains(newer);
    }
    @Test void staleExpectedVersionAndInputRevisionDoNotAppend()throws Exception {
        long u=user(),l=loan(u,false);var d=draft(u,l,100,date(3));append(u,1,d);
        assertThatThrownBy(()->append(u,1,d)).isInstanceOf(Conflict.class);
        tx.executeWithoutResult(s->revisions.bump(u,"TEST_CHANGE"));
        assertThatThrownBy(()->append(u,2,d)).isInstanceOf(Conflict.class);assertThat(plans.versions(u,l)).hasSize(1);
    }
    @Test void changedCurrentPrincipalRequiresAFreshContractSnapshot()throws Exception {
        long u=user(),l=loan(u,false);var before=draft(u,l,100,date(3));var first=append(u,1,before);
        tx.executeWithoutResult(s->{jdbc.update("UPDATE loans SET remaining_principal=11000000 WHERE id=?",l);revisions.bump(u,"TEST_BALANCE_CHANGE");});
        var stale=new Draft(l,before.mode(),null,null,before.targetPayoffDate(),100,0,date(4),revisions.current(u),before.termsAtStart(),new Inputs(100L,null,null),null);
        assertThatThrownBy(()->append(u,2,stale)).isInstanceOf(Conflict.class);
        var second=append(u,2,draft(u,l,200,date(4)));assertThat(first.draft().termsAtStart().outstanding()).isEqualTo(12000000);
        assertThat(second.draft().termsAtStart().outstanding()).isEqualTo(11000000);assertThat(plans.versions(u,l).getFirst()).isEqualTo(first);
    }
    @Test void sourceOwnershipAndQueriesHideForeignAndMissingLoans()throws Exception {
        long u=user(),v=user(),l=loan(u,false);var d=draft(u,l,100,date(3));append(u,1,d);
        assertThatThrownBy(()->append(v,1,d)).hasRootCauseInstanceOf(IllegalArgumentException.class);
        for(long id:List.of(l,Long.MAX_VALUE)) {
            assertThatThrownBy(()->plans.versions(v,id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("LOAN_NOT_FOUND"));
            assertThatThrownBy(()->plans.effectiveOn(v,id,date(3))).isInstanceOf(ApiException.class);
        }
    }
    @Test void inactiveLoanHistoryRemainsReadableButCannotReceiveNewVersions()throws Exception {
        long u=user(),l=loan(u,false);var d=draft(u,l,100,date(3));var saved=append(u,1,d);
        for(String status:List.of("PAID","DELETED")) {
            jdbc.update("UPDATE loans SET status=? WHERE id=?",status,l);
            assertThatThrownBy(()->append(u,2,d)).isInstanceOf(Conflict.class);assertThat(plans.versions(u,l)).containsExactly(saved);
        }
    }
    @Test void callerTransactionIsRequiredAndRollbackReusesTheUncommittedVersionNumber()throws Exception {
        long u=user(),l=loan(u,false);var d=draft(u,l,100,date(3));
        assertThatThrownBy(()->plans.append(u,1,d)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(s->{plans.append(u,1,d);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(plans.versions(u,l)).isEmpty();assertThat(append(u,1,d).version()).isEqualTo(2);
    }
    @Test void multipleLoanAppendsAreAtomicAndTheCoordinatorBumpsRevisionOnce()throws Exception {
        long u=user(),a=loan(u,false),b=loan(u,false);var da=draft(u,a,100,date(3));var db=draft(u,b,200,date(3));long revision=revisions.current(u);
        assertThatThrownBy(()->tx.executeWithoutResult(s->{plans.append(u,1,da);plans.append(u,99,db);})).isInstanceOf(Conflict.class);
        assertThat(plans.versions(u,a)).isEmpty();assertThat(plans.versions(u,b)).isEmpty();
        tx.executeWithoutResult(s->{plans.append(u,1,da);plans.append(u,1,db);revisions.bump(u,"TEST_PLAN_APPLY");});
        assertThat(revisions.current(u)).isEqualTo(revision+1);assertThat(plans.versions(u,a).getFirst().draft().inputRevision()).isEqualTo(revision);
        assertThat(plans.versions(u,b)).hasSize(1);
    }
    @Test void concurrentSameExpectedVersionHasOneWinnerRatherThanDuplicateHistory()throws Exception {
        long u=user(),l=loan(u,false);var d=draft(u,l,100,date(3));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<String> task=()->{start.await();try{append(u,1,d);return "OK";}catch(Conflict e){return "STALE";}};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","STALE");
        }
        assertThat(plans.versions(u,l)).hasSize(1);
    }
    @Test void unknownFeesAndZeroContractAmountStayDifferentFromMissingValues()throws Exception {
        long u=user();var body=new LoanPreviewTest().body();body.put("contractual_monthly_payment",0);long l=registration.create(u,body,null).body().path("data").path("loan_id").longValue();
        var saved=append(u,1,draft(u,l,0,date(3)));var t=saved.draft().termsAtStart();
        assertThat(t.contractualNextAmount()).isZero();assertThat(t.feeRate()).isNull();assertThat(t.feeMethod()).isEqualTo(FeeMethod.UNKNOWN);
        long other=loan(u,false);assertThat(append(u,1,draft(u,other,0,date(3))).draft().termsAtStart().contractualNextAmount()).isNull();
    }
    @Test void requestedAmountsRemainDistinctFromResolvedCashAndMissingOrExplicitZero()throws Exception {
        long u=user(),l=loan(u,false);var t=terms(u,l);long revision=revisions.current(u);
        var monthly=new Draft(l,Mode.MONTHLY_AMOUNT,null,null,date(12),740000,0,date(3),revision,t,new Inputs(null,2000000L,null),null);
        var a=append(u,1,monthly);assertThat(a.draft().inputs().monthlyAmount()).isEqualTo(2000000);assertThat(a.draft().monthlyExtraCash()).isEqualTo(740000);
        assertThat(a.draft().inputs().extraMonthlyPayment()).isNull();
        var b=append(u,2,draft(u,l,0,date(4)));assertThat(b.draft().inputs().extraMonthlyPayment()).isZero();
        var maturity=new Draft(l,Mode.MATURITY,null,null,date(12),0,0,date(5),revision,t,new Inputs(null,null,null),null);
        assertThat(append(u,3,maturity).draft().inputs().extraMonthlyPayment()).isNull();
    }
    @Test void bulletSavingAndStrategyProvenanceAreStoredWithoutPretendingToValidateCandidates()throws Exception {
        long u=user(),l=loan(u,true);var t=terms(u,l);var origin=new StrategySource(7,"strategy-version","SAVINGS");
        var d=new Draft(l,Mode.STRATEGY,null,null,date(12),0,100000,date(3),revisions.current(u),t,new Inputs(null,null,null),origin);
        var saved=append(u,1,d);assertThat(saved.draft().strategy()).isEqualTo(origin);assertThat(saved.draft().monthlySaving()).isEqualTo(100000);
        assertThat(saved.draft().monthlyExtraCash()).isZero();assertThat(saved.draft().termsAtStart().currentSavingBalance()).isEqualTo(2000000);
    }
    @Test void databaseRejectsMutationOfCommittedVersions()throws Exception {
        long u=user(),l=loan(u,false);append(u,1,draft(u,l,100,date(3)));
        assertThatThrownBy(()->jdbc.update("UPDATE repayment_plan_versions SET monthly_extra_cash=200 WHERE user_id=?",u)).hasMessageContaining("append-only");
        assertThatThrownBy(()->jdbc.update("DELETE FROM repayment_plan_versions WHERE user_id=?",u)).hasMessageContaining("append-only");
    }
    @Test void databaseRejectsForeignReferencesNegativeGoalsAndSnapshotOwnerSpoofing()throws Exception {
        long u=user(),v=user(),l=loan(u,false);append(u,1,draft(u,l,100,date(3)));
        String copy="""
            INSERT INTO repayment_plan_versions(user_id,loan_id,version,effective_date,input_data_revision,mode,monthly_extra_cash,monthly_saving,requested_extra_monthly_payment,plan_start_principal,terms_snapshot,created_at)
            SELECT ?,loan_id,99,effective_date,input_data_revision,mode,?,monthly_saving,requested_extra_monthly_payment,plan_start_principal,terms_snapshot,created_at FROM repayment_plan_versions WHERE user_id=?
            """;
        assertThatThrownBy(()->jdbc.update(copy,v,100,u)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update(copy,u,-1,u)).isInstanceOf(DataIntegrityViolationException.class);
        var nullStatus=copy.replace(",terms_snapshot,created_at FROM",",jsonb_set(terms_snapshot,'{status}','null'::jsonb),created_at FROM");
        assertThatThrownBy(()->jdbc.update(nullStatus,u,100,u)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(plans.versions(u,l)).hasSize(1);
    }
    @Test void draftCannotConfuseSavingsWithExtraPrincipalOrDropRequiredSourceFields()throws Exception {
        long u=user(),l=loan(u,true);var t=terms(u,l);
        assertThatThrownBy(()->new Draft(l,Mode.EXTRA_AMOUNT,null,null,date(12),1,0,date(3),revisions.current(u),t,new Inputs(1L,null,null),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Draft(l,Mode.PREFERENCE,null,null,date(12),0,1,date(3),revisions.current(u),t,new Inputs(null,null,1L),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new StrategySource(0,"v","CODE")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void proposalDoesNotActivateFlywayAndRuntimePortRequiresActualApplicationStorage() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",String.class)).contains("1","2").doesNotContain("4");
        assertThat(publicPlans.getIfAvailable()).isInstanceOf(com.loanrun.repaymentplan.service.SavedPlanPortService.class);
        assertThatThrownBy(()->publicPlans.getObject().overallState(user())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
    @Test void registrationUsesOriginalBalanceAndDoesNotInventMissingHistoricalTerms()throws Exception {
        long u=user(),l=loan(u,false);jdbc.update("UPDATE loans SET remaining_principal=9000000,snapshot_principal=9000000,snapshot_date='2026-03-02' WHERE id=?",l);
        clock.value=Instant.parse("2026-03-02T00:00:00Z");var original=queries.registration(u,l);
        assertThat(original.origin()).isEqualTo(Origin.REGISTRATION);assertThat(original.startPrincipal()).isEqualTo(12000000);
        assertThat(original.basisTerms()).isNull();assertThat(original.inputs()).isNull();assertThat(original.monthlyExtraCash()).isZero();
        assertThat(original.version()).isEqualTo(1);assertThat(original.targetPayoffDate()).isEqualTo(date(12));
    }
    @Test void dateSelectionCombinesV2BaselineWithOnlyEffectiveStoredVersions()throws Exception {
        long u=user(),l=loan(u,false);var v=append(u,1,draft(u,l,100,date(4)));
        assertThat(queries.forDate(u,l,date(3),date(3).withDayOfMonth(1)).orElseThrow().origin()).isEqualTo(Origin.REGISTRATION);
        assertThat(queries.forDate(u,l,date(4),LocalDate.of(2026,3,1)).orElseThrow().version()).isEqualTo(v.version());
        assertThat(queries.forDate(u,l,LocalDate.of(2026,3,24),LocalDate.of(2026,3,1))).isEmpty();
    }
    @Test void observationCutoffKeepsLaterRecordedGoalsOutOfEarlierKnowledge()throws Exception {
        long u=user(),l=loan(u,false);clock.value=Instant.parse("2026-03-19T15:00:00Z");append(u,1,draft(u,l,100,date(3)));
        assertThat(queries.forDate(u,l,date(3),LocalDate.of(2026,3,19)).orElseThrow().version()).isEqualTo(1);
        assertThat(queries.forDate(u,l,date(3),LocalDate.of(2026,3,20)).orElseThrow().version()).isEqualTo(2);
        assertThat(queries.forDate(u,l,date(3),LocalDate.of(2026,2,28))).isEmpty();
    }
    @Test void highestApplicableVersionPreservesItsOwnBasisAndRequestedZero()throws Exception {
        long u=user(),l=loan(u,false);append(u,1,draft(u,l,100,date(4)));append(u,2,draft(u,l,0,date(4)));
        var plan=queries.forDate(u,l,date(4),LocalDate.of(2026,3,1)).orElseThrow();
        assertThat(plan.version()).isEqualTo(3);assertThat(plan.origin()).isEqualTo(Origin.PLAN_VERSION);
        assertThat(plan.inputs().extraMonthlyPayment()).isZero();assertThat(plan.basisTerms().outstanding()).isEqualTo(12000000);
        assertThat(plan.startPrincipal()).isEqualTo(12000000);
    }
    @Test void missingRegistrationBalanceIsUnknownRatherThanCurrentBalance()throws Exception {
        long u=user(),l=loan(u,false);jdbc.update("DELETE FROM loan_balance_snapshots WHERE loan_id=?",l);
        assertThat(queries.registration(u,l).startPrincipal()).isNull();
    }
    @Test void laterRecordedBaselineEvidenceIsNotClaimedKnownAtAnEarlierCutoff()throws Exception {
        long u=user(),l=loan(u,false);clock.value=Instant.parse("2026-03-19T15:00:00Z");
        jdbc.update("UPDATE loan_balance_snapshots SET created_at=? WHERE loan_id=?",Timestamp.from(clock.instant()),l);
        assertThat(queries.forDate(u,l,date(3),LocalDate.of(2026,3,19)).orElseThrow().startPrincipal()).isNull();
        assertThat(queries.forDate(u,l,date(3),LocalDate.of(2026,3,20)).orElseThrow().startPrincipal()).isEqualTo(12000000);
    }
    @Test void sourceQueriesPreserveInactiveHistoryButHideForeignOwnership()throws Exception {
        long u=user(),other=user(),l=loan(u,false);append(u,1,draft(u,l,100,date(3)));jdbc.update("UPDATE loans SET status='DELETED' WHERE id=?",l);
        assertThat(queries.forDate(u,l,date(3),LocalDate.of(2026,3,1)).orElseThrow().version()).isEqualTo(2);
        assertThatThrownBy(()->queries.registration(other,l)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("LOAN_NOT_FOUND"));
        assertThatThrownBy(()->queries.forDate(other,l,date(3),LocalDate.of(2026,3,1))).isInstanceOf(ApiException.class);
    }
    @Test void sourceReadsDoNotWriteVersionsRevisionOrFinancialFacts()throws Exception {
        long u=user(),l=loan(u,false);append(u,1,draft(u,l,100,date(3)));var before=sources.loanSnapshot(u,l,date(3));var history=plans.versions(u,l);long revision=revisions.current(u);
        assertThat(queries.forDate(u,l,date(3),LocalDate.of(2026,3,1))).isEqualTo(queries.forDate(u,l,date(3),LocalDate.of(2026,3,1)));
        assertThat(sources.loanSnapshot(u,l,date(3))).isEqualTo(before);assertThat(plans.versions(u,l)).isEqualTo(history);assertThat(revisions.current(u)).isEqualTo(revision);
    }
}
