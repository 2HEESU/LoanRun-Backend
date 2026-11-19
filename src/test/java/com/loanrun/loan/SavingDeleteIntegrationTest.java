package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.repaymentsaving.service.SavingListService;
import java.net.*;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
@Import(SavingDeleteIntegrationTest.Config.class)
class SavingDeleteIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-02-28T15:30:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}
        public Clock withZone(ZoneId zone){return Clock.fixed(value,zone);}
        public Instant instant(){return value;}
    }
    @TestConfiguration static class Config {@Bean @Primary TestClock savingClock(){return new TestClock();}}
    @MockitoBean BudgetRecalculationPort budgets; // Registration fixture only; the GET needs no recalculation.
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoanSourceRepository sources;
    @Autowired TestClock clock;
    @LocalServerPort int port;
    @BeforeEach void reset(){clock.value=Instant.parse("2026-02-28T15:30:00Z");org.mockito.Mockito.reset(budgets);}
    void day(int day){clock.value=LocalDate.of(2026,3,day).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();}
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> get(HttpClient b,String path)throws Exception{return b.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    long owner(HttpClient b)throws Exception{return json(get(b,"/api/users/me/")).path("data").path("user_id").longValue();}
    String path(long id){return "/api/repayment-savings/?loan_id="+id;}
    ObjectNode input()throws Exception {var body=new LoanPreviewTest().body();body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);return body;}
    long loan(HttpClient b,ObjectNode input)throws Exception {
        var r=b.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/loans/"))
            .header("Origin","http://localhost:5173").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);return json(r).path("data").path("loan_id").longValue();
    }
    JsonNode list(HttpClient b,long id,String filters)throws Exception {var r=get(b,path(id)+filters);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return json(r).path("data");}
    long saving(long user,long loan,String date,long amount){return jdbc.queryForObject("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,?,?,?) RETURNING id",Long.class,user,loan,LocalDate.parse(date),amount,Timestamp.from(clock.instant()));}
    long payment(long user,long loan,String date,long used,long interest){return jdbc.queryForObject("""
        INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at)
        VALUES (?,?,?,'REGULAR',?,?,0,?,0,?,?) RETURNING id
        """,Long.class,user,loan,LocalDate.parse(date),used+interest,used,interest,used,Timestamp.from(clock.instant()));}

    HttpResponse<String> delete(HttpClient browser,long saving,String body,String key)throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repayment-savings/"+saving+"/"))
            .header("Origin","http://localhost:5173");
        if(key!=null)r.header("Idempotency-Key",key);
        if(body==null)r.DELETE();else r.header("Content-Type","application/json").method("DELETE",HttpRequest.BodyPublishers.ofString(body));
        return browser.send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
    long count(String table,long user){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE user_id=?",Long.class,user);}
    long revision(long user){return jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,user);}
    String key(){return UUID.randomUUID().toString();}

    @Test void deletesOnlySelectedRecordAndReusesCurrentSummaryWithoutChangingLoanOrPayments()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),target=saving(user,id,"2026-03-01",100000),kept=saving(user,id,"2026-03-01",200000);clearInvocations(budgets);
        var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));long rev=revision(user);
        var r=delete(b,target,null,key());assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).path("code").asText()).isEqualTo("SAVING_DELETE_SUCCESS");assertThat(json(r).path("message").asText()).isEqualTo("상환자금 적립 기록 삭제 성공");
        var d=json(r).path("data");assertThat(d.path("saving_id").longValue()).isEqualTo(target);assertThat(d.path("deleted").booleanValue()).isTrue();
        assertThat(d.path("loan_id").longValue()).isEqualTo(id);assertThat(d.path("savings_balance").longValue()).isEqualTo(2200000);
        assertThat(d.path("required_monthly_saving").longValue()).isEqualTo(980000);assertThat(d.path("planned_saving_amount").longValue()).isEqualTo(1000000);
        assertThat(d.path("actual_saving_amount").longValue()).isEqualTo(200000);assertThat(d.path("saving_progress_pct").decimalValue()).isEqualByComparingTo("18.33");
        assertThat(d.path("remaining_principal").longValue()).isEqualTo(12000000);assertThat(d.path("report_stale").booleanValue()).isTrue();
        var after=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));assertThat(after.loans()).isEqualTo(before.loans());assertThat(after.payments()).isEqualTo(before.payments());
        assertThat(after.dues()).isEqualTo(before.dues());assertThat(after.allocations()).isEqualTo(before.allocations());assertThat(after.rates()).isEqualTo(before.rates());assertThat(after.balances()).isEqualTo(before.balances());
        assertThat(after.savings()).containsExactly(before.savings().get(1));assertThat(after.savings().getFirst().id()).isEqualTo(kept);
        assertThat(revision(user)).isEqualTo(rev+1);assertThat(count("idempotency_records",user)).isEqualTo(1);verify(budgets).recalculateFrom(user,YearMonth.of(2026,3));
        assertThat(list(b,id,"").path("savings_balance")).isEqualTo(d.path("savings_balance"));
    }
    @Test void sameKeyReplaysAfterDeletionAndClosureButDifferentKeySeesMissingResource()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),target=saving(user,id,"2026-03-01",100);clearInvocations(budgets);String key=key();
        var first=delete(b,target,null,key);assertThat(first.statusCode()).isEqualTo(200);long rev=revision(user);
        jdbc.update("UPDATE loans SET status='DELETED',snapshot_date='2026-03-10' WHERE id=?",id);day(20);
        var replay=delete(b,target,null,key);assertThat(replay.statusCode()).isEqualTo(200);assertThat(json(replay)).isEqualTo(json(first));
        assertThat(delete(b,target,null,key()).statusCode()).isEqualTo(404);assertThat(delete(b,target,"{}",key).statusCode()).isEqualTo(409);
        assertThat(revision(user)).isEqualTo(rev);assertThat(count("repayment_savings",user)).isZero();verify(budgets).recalculateFrom(eq(user),any());
    }
    @Test void requiredKeyAndBodylessContractAreEnforcedWithoutWrites()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),target=saving(user,id,"2026-03-01",100);long rev=revision(user);
        for(String key:Arrays.asList(null,"","x".repeat(129))) {
            var r=delete(b,target,null,key);assertThat(r.statusCode()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("SAVING_VALIDATION_ERROR");
        }
        for(String body:List.of("{}","null","[]"," ","{\"amount\":100}"))assertThat(delete(b,target,body,key()).statusCode()).isEqualTo(400);
        assertThat(count("repayment_savings",user)).isEqualTo(1);assertThat(count("idempotency_records",user)).isZero();assertThat(revision(user)).isEqualTo(rev);
    }
    @Test void foreignAndMissingRecordHaveIdentical404AndKeyScopeIsUserSpecific()throws Exception {
        var a=browser();var b=browser();long ua=owner(a),ub=owner(b),la=loan(a,input()),lb=loan(b,input());
        long sa=saving(ua,la,"2026-03-01",100),sb=saving(ub,lb,"2026-03-01",100);
        var foreign=delete(b,sa,null,key());var missing=delete(b,Long.MAX_VALUE,null,key());
        assertThat(foreign.statusCode()).isEqualTo(404);assertThat(json(foreign)).isEqualTo(json(missing));assertThat(json(foreign).path("code").asText()).isEqualTo("SAVING_NOT_FOUND");
        String key=key();assertThat(delete(a,sa,null,key).statusCode()).isEqualTo(200);assertThat(delete(b,sb,null,key).statusCode()).isEqualTo(200);
    }
    @Test void inactiveAndHistoricallyLockedRecordsConflictAndFutureOrNonBulletAreInvalid()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),target=saving(user,id,"2026-03-01",100);day(20);
        jdbc.update("UPDATE loans SET snapshot_date='2026-03-02' WHERE id=?",id);assertThat(delete(b,target,null,key()).statusCode()).isEqualTo(409);
        jdbc.update("UPDATE loans SET snapshot_date='2026-03-01' WHERE id=?",id);
        for(String status:List.of("PAID","DELETED")) {jdbc.update("UPDATE loans SET status=? WHERE id=?",status,id);assertThat(delete(b,target,null,key()).statusCode()).isEqualTo(409);}
        long installment=loan(b,new LoanPreviewTest().body()),si=saving(user,installment,"2026-03-20",100);
        assertThat(delete(b,si,null,key()).statusCode()).isEqualTo(400);
        long active=loan(b,input()),future=saving(user,active,"2026-03-21",100);assertThat(delete(b,future,null,key()).statusCode()).isEqualTo(400);
        long boundary=saving(user,active,"2026-03-20",100);assertThat(delete(b,boundary,null,key()).statusCode()).isEqualTo(200);
    }
    @Test void usedFundsCannotBeDeletedEvenWhenLaterDepositsKeepFinalBalancePositive()throws Exception {
        var b=browser();long user=owner(b);var input=input();input.put("initial_savings_balance",0);long id=loan(b,input);day(20);
        long target=saving(user,id,"2026-03-02",100);payment(user,id,"2026-03-03",80,0);saving(user,id,"2026-03-04",200);
        var r=delete(b,target,null,key());assertThat(r.statusCode()).isEqualTo(409);assertThat(json(r).path("code").asText()).isEqualTo("SAVING_CONFLICT");
        assertThat(count("repayment_savings",user)).isEqualTo(2);assertThat(count("idempotency_records",user)).isZero();
    }
    @Test void ambiguousSameDayRemovalIsRejectedButIndependentEarlierAssetsMakeItSafe()throws Exception {
        var b=browser();long user=owner(b);var input=input();input.put("initial_savings_balance",0);long id=loan(b,input);day(20);
        long target=saving(user,id,"2026-03-02",100);saving(user,id,"2026-03-02",100);payment(user,id,"2026-03-02",80,0);
        assertThat(delete(b,target,null,key()).statusCode()).isEqualTo(409); // New day-end assets would still be +20.
        jdbc.update("UPDATE loans SET initial_savings_balance=80 WHERE id=?",id);
        var r=delete(b,target,null,key());assertThat(r.statusCode()).isEqualTo(200);assertThat(json(r).path("data").path("savings_balance").longValue()).isEqualTo(100);
    }
    @Test void safeLaterDayUseAndUnrelatedEarlierSameDayUseDoNotBlockRemoval()throws Exception {
        var b=browser();long user=owner(b);var input=input();input.put("initial_savings_balance",0);long id=loan(b,input);day(20);
        saving(user,id,"2026-03-02",100);payment(user,id,"2026-03-02",80,0);long target=saving(user,id,"2026-03-05",100);
        assertThat(delete(b,target,null,key()).statusCode()).isEqualTo(200);
        long target2=saving(user,id,"2026-03-06",10);payment(user,id,"2026-03-07",10,0);
        assertThat(delete(b,target2,null,key()).statusCode()).isEqualTo(200); // Previous assets still cover later use.
    }
    @Test void historicalDeletionRecalculatesOriginalMonthAndPreservesCurrentMonthRecordedAdditions()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),target=saving(user,id,"2026-03-01",100);clearInvocations(budgets);
        clock.value=Instant.parse("2026-03-31T15:00:00Z");saving(user,id,"2026-04-01",200);
        var r=delete(b,target,null,key());assertThat(r.statusCode()).isEqualTo(200);assertThat(json(r).path("data").path("actual_saving_amount").longValue()).isEqualTo(200);
        verify(budgets).recalculateFrom(user,YearMonth.of(2026,3));
    }
    @Test void recalculationFailureRestoresDeletedRecordRevisionAndKeyAndRetryWorks()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),target=saving(user,id,"2026-03-01",100);long rev=revision(user);String key=key();
        var original=jdbc.queryForMap("SELECT * FROM repayment_savings WHERE id=?",target);
        doAnswer(call->{assertThat(count("repayment_savings",user)).isZero();throw new IllegalStateException("test rollback");}).when(budgets).recalculateFrom(eq(user),any());
        assertThat(delete(b,target,null,key).statusCode()).isEqualTo(500);assertThat(jdbc.queryForMap("SELECT * FROM repayment_savings WHERE id=?",target)).isEqualTo(original);
        assertThat(revision(user)).isEqualTo(rev);assertThat(count("idempotency_records",user)).isZero();
        doNothing().when(budgets).recalculateFrom(eq(user),any());assertThat(delete(b,target,null,key).statusCode()).isEqualTo(200);
    }
    @Test void projectionFailureAfterRevisionStillRestoresAllChanges()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),target=saving(user,id,"2026-03-01",100);long rev=revision(user);
        doAnswer(call->{jdbc.update("UPDATE loans SET initial_plan_snapshot='{}'::jsonb WHERE id=?",id);return null;}).when(budgets).recalculateFrom(eq(user),any());
        assertThat(delete(b,target,null,key()).statusCode()).isEqualTo(503);
        assertThat(count("repayment_savings",user)).isEqualTo(1);assertThat(count("idempotency_records",user)).isZero();assertThat(revision(user)).isEqualTo(rev);
        assertThat(sources.loan(user,id).initialPlan().version()).isEqualTo(1);
    }
    @Test void concurrentSameKeyDeletesOnceAndReplaysTheSameResponse()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),target=saving(user,id,"2026-03-01",100);clearInvocations(budgets);long rev=revision(user);String key=key();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<HttpResponse<String>> task=()->{start.await();return delete(b,target,null,key);};
            var a=pool.submit(task);var c=pool.submit(task);start.countDown();var first=a.get(15,TimeUnit.SECONDS);var second=c.get(15,TimeUnit.SECONDS);
            assertThat(first.statusCode()).isEqualTo(200);assertThat(second.statusCode()).isEqualTo(200);assertThat(json(second)).isEqualTo(json(first));
        }
        assertThat(count("repayment_savings",user)).isZero();assertThat(revision(user)).isEqualTo(rev+1);verify(budgets).recalculateFrom(eq(user),any());
    }
    @Test void concurrentDifferentKeysForOneRecordHaveOneDeleteAndOne404()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),target=saving(user,id,"2026-03-01",100);clearInvocations(budgets);long rev=revision(user);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<Integer> task=()->{start.await();return delete(b,target,null,key()).statusCode();};
            var a=pool.submit(task);var c=pool.submit(task);start.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),c.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,404);
        }
        assertThat(revision(user)).isEqualTo(rev+1);assertThat(count("idempotency_records",user)).isEqualTo(1);verify(budgets).recalculateFrom(eq(user),any());
    }
    @Test void concurrentDeletesOfTwoSourcesCannotBothSpendTheSameRemainingCollateral()throws Exception {
        var b=browser();long user=owner(b);var input=input();input.put("initial_savings_balance",0);long id=loan(b,input);day(20);
        long a=saving(user,id,"2026-03-02",100),c=saving(user,id,"2026-03-02",100);payment(user,id,"2026-03-03",100,0);clearInvocations(budgets);long rev=revision(user);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var first=pool.submit(()->{start.await();return delete(b,a,null,key()).statusCode();});
            var second=pool.submit(()->{start.await();return delete(b,c,null,key()).statusCode();});
            start.countDown();assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(count("repayment_savings",user)).isEqualTo(1);assertThat(list(b,id,"").path("savings_balance").longValue()).isZero();
        assertThat(revision(user)).isEqualTo(rev+1);verify(budgets).recalculateFrom(eq(user),any());
    }
    @Test void malformedSourceDoesNotTurnDeletionIntoFakeSuccessAndExactSlashIsRequired()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),target=saving(user,id,"2026-03-01",100);
        jdbc.update("UPDATE loans SET initial_plan_snapshot='{}'::jsonb WHERE id=?",id);assertThat(delete(b,target,null,key()).statusCode()).isEqualTo(503);
        var r=b.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repayment-savings/"+target))
            .header("Origin","http://localhost:5173").header("Idempotency-Key",key()).DELETE().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(404);assertThat(count("repayment_savings",user)).isEqualTo(1);
    }
}
