package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.service.LoanRegistrationService;
import com.loanrun.repaymentplan.domain.PlanVersion.*;
import com.loanrun.repaymentplan.read.RegistrationPlanFallback;
import com.loanrun.repaymentplan.repository.*;
import java.nio.file.*;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest
@Import(SavedPlanPortIntegrationTest.Config.class)
class SavedPlanPortIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-02-28T15:30:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}public Clock withZone(ZoneId z){return Clock.fixed(value,z);}public Instant instant(){return value;}
    }
    @TestConfiguration static class Config {@Bean @Primary TestClock planClock(){return new TestClock();}}
    @MockitoBean BudgetRecalculationPort budgets;
    @Autowired PlanQueryPort plans;
    @Autowired LoanQueryPort loans;
    @Autowired LoanRegistrationService registration;
    @Autowired PlanVersionRepository versions;
    @Autowired PlanApplicationRepository applications;
    @Autowired RevisionPort revisions;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @BeforeEach void storage()throws Exception {
        clock.value=Instant.parse("2026-02-28T15:30:00Z");
        for(String table:List.of("repayment_plan_versions","plan_application_events"))if(jdbc.queryForObject("SELECT to_regclass(?)",String.class,table)==null)
            jdbc.execute(Files.readString(Path.of("docs/sql/"+table+"_proposal.sql")));
    }
    long user(){var now=Timestamp.from(clock.instant());return jdbc.queryForObject("INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,?,?) RETURNING id",Long.class,UUID.randomUUID().toString(),now,now);}
    long loan(long u,boolean bullet)throws Exception {
        var body=new LoanPreviewTest().body();if(bullet){body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);}
        return registration.create(u,body,null).body().path("data").path("loan_id").longValue();
    }
    LocalDate day(int month,int day){return LocalDate.of(2026,month,day);}
    void extra(long u,long id,LocalDate effective,long amount) {
        var t=loans.terms(u,id,day(3,1));var draft=new Draft(id,Mode.EXTRA_AMOUNT,null,null,t.maturityDate(),amount,0,effective,revisions.current(u),t,new Inputs(amount,null,null),null);
        tx.execute(s->versions.append(u,1,draft));
    }
    @Test void currentDefaultPlansUseRealGoalsAndEngineForecastWithoutBudgetQueries()throws Exception {
        long u=user(),id=loan(u,false);var plan=plans.currentPlans(u,day(3,1)).getFirst();
        assertThat(plan.loanId()).isEqualTo(id);assertThat(plan.version()).isEqualTo(1);assertThat(plan.monthlyExtraCash()).isZero();
        assertThat(plan.projectedPayoffDate()).isEqualTo(day(12,25));assertThat(plan.targetPayoffDate()).isEqualTo(day(12,25));
        assertThat(((RegistrationPlanFallback)plans).registrationFallbackAllowed()).isFalse();
    }
    @Test void ownerQueriesNeverIncludeOtherUsersOrInactiveLoans()throws Exception {
        long a=user(),b=user(),first=loan(a,false),second=loan(b,false),closed=loan(a,false);
        jdbc.update("UPDATE loans SET status='PAID',remaining_principal=0 WHERE id=?",closed);
        assertThat(plans.currentPlans(a,day(3,1))).extracting(PlanSnapshot::loanId).containsExactly(first);
        assertThat(plans.currentPlans(b,day(3,1))).extracting(PlanSnapshot::loanId).containsExactly(second);
        assertThat(plans.currentPlans(user(),day(3,1))).isEmpty();
    }
    @Test void futureEffectiveGoalDoesNotReplaceThisMonthsTargetOrRegistrationSnapshot()throws Exception {
        long u=user(),id=loan(u,false);extra(u,id,day(4,25),100000);
        assertThat(plans.currentPlans(u,day(3,1)).getFirst().version()).isEqualTo(1);
        assertThat(plans.plannedAllocations(u,YearMonth.of(2026,3)).getFirst().cashAmount()).isZero();
        assertThat(plans.plannedAllocations(u,YearMonth.of(2026,4)).getFirst().cashAmount()).isEqualTo(100000);
        assertThat(jdbc.queryForObject("SELECT initial_plan_snapshot->>'version' FROM loans WHERE id=?",String.class,id)).isEqualTo("1");
    }
    @Test void savingRequirementIsCalculatedFromAssetsAndTargetInsteadOfDuplicatingTheSavedGoal()throws Exception {
        long u=user(),id=loan(u,true);var t=loans.terms(u,id,day(3,1));
        var draft=new Draft(id,Mode.TARGET_DATE,null,day(12,25),day(12,25),0,800000,day(3,25),revisions.current(u),t,new Inputs(null,null,800000L),null);
        tx.execute(s->versions.append(u,1,draft));var plan=plans.currentPlans(u,day(3,1)).getFirst();
        assertThat(plan.monthlySaving()).isEqualTo(800000);assertThat(plan.requiredMonthlySaving()).isEqualTo(1000000);
    }
    @Test void actualApplicationStateIsOwnerScopedAndFutureEventsDoNotLeakIntoCurrentState()throws Exception {
        long u=user(),id=loan(u,false),other=user();var before=plans.overallState(u);assertThat(before.exists()).isFalse();
        applications.append(u,revisions.current(u),true,"FAST",List.of(new PlannedAmount(id,AllocationType.EXTRA_PAYMENT,0,2)),clock.instant().plusSeconds(60));
        assertThat(plans.overallState(u).exists()).isFalse();clock.value=clock.value.plusSeconds(61);
        assertThat(plans.overallState(u).strategyCode()).isEqualTo("FAST");assertThat(plans.overallState(other).exists()).isFalse();
        assertThat(plans.plannedAllocations(u,YearMonth.of(2026,3)).getFirst().priority()).isEqualTo(2);
    }
    @Test void unchangedHistoricalGoalsAreAvailableAndForecastsDoNotUseTodaysKnowledgeAsPastForecasts()throws Exception {
        long u=user(),id=loan(u,false);clock.value=Instant.parse("2026-03-31T15:00:00Z");
        assertThat(plans.currentPlans(u,day(3,1))).singleElement().satisfies(p->{assertThat(p.loanId()).isEqualTo(id);assertThat(p.projectedPayoffDate()).isNull();});
        assertThat(plans.plannedAllocations(u,YearMonth.of(2026,3))).singleElement().satisfies(a->assertThat(a.cashAmount()).isZero());
        jdbc.update("UPDATE loans SET updated_at='2026-04-01T00:00:00Z' WHERE id=?",id);
        assertThatThrownBy(()->plans.plannedAllocations(u,YearMonth.of(2026,3))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
    @Test void frozenUnsettledBasisKeepsGoalsButDoesNotInventAPayoffDate()throws Exception {
        long u=user();loan(u,false);clock.value=Instant.parse("2026-03-31T15:00:00Z");
        assertThat(plans.currentPlans(u,day(4,1))).singleElement().satisfies(p->{assertThat(p.configured()).isTrue();assertThat(p.projectedPayoffDate()).isNull();});
    }
    @Test void readsLeaveVersionsApplicationsRevisionAndLedgerUnchanged()throws Exception {
        long u=user();loan(u,false);var user=jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",u);var ledger=jdbc.queryForList("SELECT * FROM loans WHERE user_id=?",u);
        plans.currentPlans(u,day(3,1));plans.plannedAllocations(u,YearMonth.of(2026,3));plans.overallState(u);
        assertThat(jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",u)).isEqualTo(user);assertThat(jdbc.queryForList("SELECT * FROM loans WHERE user_id=?",u)).isEqualTo(ledger);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repayment_plan_versions WHERE user_id=?",Long.class,u)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_application_events WHERE user_id=?",Long.class,u)).isZero();
    }
    @Test void expiredZeroDueUsesTheVerifiedNextCycleForSelectingFutureEffectiveGoals()throws Exception {
        long u=user(),id=loan(u,false);extra(u,id,day(4,25),100000);
        jdbc.update("UPDATE due_items SET required_amount=0,principal_amount=0,interest_amount=0 WHERE loan_id=?",id);
        clock.value=Instant.parse("2026-03-25T15:00:00Z");
        var plan=plans.currentPlans(u,day(3,26)).getFirst();assertThat(plan.version()).isEqualTo(2);assertThat(plan.monthlyExtraCash()).isEqualTo(100000);
    }
}
