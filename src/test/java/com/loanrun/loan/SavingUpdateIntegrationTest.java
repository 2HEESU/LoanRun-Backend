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
@Import(SavingUpdateIntegrationTest.Config.class)
class SavingUpdateIntegrationTest {
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

    HttpResponse<String> patch(HttpClient browser,long saving,String body,String key)throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repayment-savings/"+saving+"/"))
            .header("Content-Type","application/json").header("Origin","http://localhost:5173");
        if(key!=null)r.header("Idempotency-Key",key);
        return browser.send(r.method("PATCH",HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    long count(String table,long user){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE user_id=?",Long.class,user);}
    long revision(long user){return jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,user);}
    String key(){return UUID.randomUUID().toString();}

    @Test void changesOnlySuppliedAmountAndPreservesDateCreationMemoAndFinancialSources()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),saving=saving(user,id,"2026-03-01",100000);clearInvocations(budgets);
        jdbc.update("UPDATE repayment_savings SET memo='보존' WHERE id=?",saving);var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));long rev=revision(user);
        var r=patch(b,saving,"{\"amount\":120000}",key());assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).path("code").asText()).isEqualTo("SAVING_UPDATE_SUCCESS");assertThat(json(r).path("message").asText()).isEqualTo("상환자금 적립 기록 수정 성공");
        var d=json(r).path("data");assertThat(d.path("saving").path("saved_at").asText()).isEqualTo("2026-03-01");
        assertThat(d.path("saving").path("amount").longValue()).isEqualTo(120000);assertThat(d.path("saving").path("created_at").asText()).isEqualTo("2026-03-01T00:30:00+09:00");
        assertThat(d.path("savings_balance").longValue()).isEqualTo(2120000);assertThat(d.path("required_monthly_saving").longValue()).isEqualTo(988000);
        assertThat(d.path("actual_saving_amount").longValue()).isEqualTo(120000);assertThat(d.path("report_stale").booleanValue()).isTrue();
        var after=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));assertThat(after.loans()).isEqualTo(before.loans());assertThat(after.dues()).isEqualTo(before.dues());
        assertThat(after.payments()).isEqualTo(before.payments());assertThat(after.balances()).isEqualTo(before.balances());assertThat(after.rates()).isEqualTo(before.rates());
        assertThat(after.savings().getFirst().memo()).isEqualTo("보존");assertThat(count("repayment_savings",user)).isEqualTo(1);assertThat(revision(user)).isEqualTo(rev+1);
        verify(budgets).recalculateFrom(user,YearMonth.of(2026,3));assertThat(list(b,id,"").path("results").get(0)).isEqualTo(d.path("saving"));
    }
    @Test void dateOnlyAndCombinedChangesRecalculateFromEarlierOldOrNewMonth()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),saving=saving(user,id,"2026-03-01",100);clearInvocations(budgets);
        clock.value=Instant.parse("2026-04-05T00:00:00Z");
        var r=patch(b,saving,"{\"saved_at\":\"2026-04-02\"}",key());assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).path("data").path("saving").path("amount").longValue()).isEqualTo(100);
        assertThat(json(r).path("data").path("actual_saving_amount").longValue()).isEqualTo(100);verify(budgets).recalculateFrom(user,YearMonth.of(2026,3));
        clearInvocations(budgets);r=patch(b,saving,"{\"saved_at\":\"2026-03-02\",\"amount\":90}",key());assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).path("data").path("actual_saving_amount").longValue()).isZero();verify(budgets).recalculateFrom(user,YearMonth.of(2026,3));
    }
    @Test void emptyOrSameValuePatchDoesNotRecalculateOrBumpRevision()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),saving=saving(user,id,"2026-03-01",100);clearInvocations(budgets);long rev=revision(user);
        var before=jdbc.queryForMap("SELECT * FROM repayment_savings WHERE id=?",saving);
        for(String body:List.of("{}","{\"amount\":100}","{\"saved_at\":\"2026-03-01\"}")) {
            var r=patch(b,saving,body,key());assertThat(r.statusCode()).isEqualTo(200);assertThat(json(r).path("data").path("report_stale").booleanValue()).isFalse();
        }
        assertThat(jdbc.queryForMap("SELECT * FROM repayment_savings WHERE id=?",saving)).isEqualTo(before);
        assertThat(revision(user)).isEqualTo(rev);verifyNoInteractions(budgets);assertThat(count("idempotency_records",user)).isEqualTo(3);
    }
    @ParameterizedTest @ValueSource(strings={"null","[]","{\"amount\":null}","{\"saved_at\":null}","{\"amount\":0}","{\"amount\":-1}","{\"amount\":1.0}","{\"amount\":\"1\"}","{\"amount\":9223372036854775808}","{\"saved_at\":\"2026-02-30\"}","{\"saved_at\":\"2026-3-01\"}","{\"loan_id\":1}","{\"memo\":\"x\"}"})
    void invalidInputRejectsNullsAndUnspecifiedFields(String body)throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),saving=saving(user,id,"2026-03-01",100);long rev=revision(user);
        var r=patch(b,saving,body,key());assertThat(r.statusCode()).as(r.body()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("SAVING_VALIDATION_ERROR");
        assertThat(count("idempotency_records",user)).isZero();assertThat(revision(user)).isEqualTo(rev);
    }
    @Test void foreignAndMissingRecordsShare404AndInvalidKeysWriteNothing()throws Exception {
        var a=browser();var b=browser();long user=owner(a),id=loan(a,input()),saving=saving(user,id,"2026-03-01",100);
        var foreign=patch(b,saving,"{\"amount\":200}",key());var missing=patch(b,Long.MAX_VALUE,"{\"amount\":200}",key());
        assertThat(foreign.statusCode()).isEqualTo(404);assertThat(json(foreign)).isEqualTo(json(missing));assertThat(json(foreign).path("code").asText()).isEqualTo("SAVING_NOT_FOUND");
        for(String key:Arrays.asList(null,"","x".repeat(129)))assertThat(patch(a,saving,"{}",key).statusCode()).isEqualTo(400);
        assertThat(count("idempotency_records",user)).isZero();
    }
    @Test void pastLockedOrInactiveRecordsConflictEvenForNoChange()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),saving=saving(user,id,"2026-03-01",100);day(20);
        jdbc.update("UPDATE loans SET snapshot_date='2026-03-02' WHERE id=?",id);assertThat(patch(b,saving,"{}",key()).statusCode()).isEqualTo(409);
        jdbc.update("UPDATE loans SET snapshot_date='2026-03-01' WHERE id=?",id);
        for(String status:List.of("PAID","DELETED")) {jdbc.update("UPDATE loans SET status=? WHERE id=?",status,id);assertThat(patch(b,saving,"{}",key()).statusCode()).isEqualTo(409);}
    }
    @Test void snapshotBoundaryIsAllowedButFutureOrEarlierDatesAreInvalid()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),saving=saving(user,id,"2026-03-05",100);day(20);
        jdbc.update("UPDATE loans SET snapshot_date='2026-03-05' WHERE id=?",id);
        assertThat(patch(b,saving,"{\"saved_at\":\"2026-03-04\"}",key()).statusCode()).isEqualTo(400);
        assertThat(patch(b,saving,"{\"saved_at\":\"2026-03-21\"}",key()).statusCode()).isEqualTo(400);
        assertThat(patch(b,saving,"{\"amount\":200}",key()).statusCode()).isEqualTo(200);
    }
    @Test void reductionIsAllowedWhenEarlierAssetsCoverEveryUseAndRejectedWhenInsufficient()throws Exception {
        var b=browser();long user=owner(b);var input=input();input.put("initial_savings_balance",0);long id=loan(b,input);day(20);
        long saving=saving(user,id,"2026-03-02",100);payment(user,id,"2026-03-03",80,0);
        assertThat(patch(b,saving,"{\"amount\":90}",key()).statusCode()).isEqualTo(200);
        var r=patch(b,saving,"{\"amount\":79}",key());assertThat(r.statusCode()).isEqualTo(409);assertThat(json(r).path("code").asText()).isEqualTo("SAVING_CONFLICT");
        assertThat(list(b,id,"").path("savings_balance").longValue()).isEqualTo(10);
    }
    @Test void movingSavingAfterItsUseCannotHideNegativeHistoryBehindPositiveFinalBalance()throws Exception {
        var b=browser();long user=owner(b);var input=input();input.put("initial_savings_balance",0);long id=loan(b,input);day(20);
        long saving=saving(user,id,"2026-03-02",100);payment(user,id,"2026-03-03",80,0);
        assertThat(patch(b,saving,"{\"saved_at\":\"2026-03-04\",\"amount\":200}",key()).statusCode()).isEqualTo(409);
        assertThat(list(b,id,"").path("results").get(0).path("saved_at").asText()).isEqualTo("2026-03-02");
    }
    @Test void ambiguousSameDayReductionConflictsButIncreaseAndEarlierFundedReductionWork()throws Exception {
        var b=browser();long user=owner(b);var input=input();input.put("initial_savings_balance",0);long id=loan(b,input);day(20);
        long saving=saving(user,id,"2026-03-02",100);payment(user,id,"2026-03-02",80,0);
        assertThat(patch(b,saving,"{\"amount\":90}",key()).statusCode()).isEqualTo(409);
        assertThat(patch(b,saving,"{\"amount\":110}",key()).statusCode()).isEqualTo(200);
        // Original initial funds make the decrease safe regardless of same-day ordering.
        jdbc.update("UPDATE loans SET initial_savings_balance=100 WHERE id=?",id);
        assertThat(patch(b,saving,"{\"amount\":90}",key()).statusCode()).isEqualTo(200);
    }
    @Test void laterDateWithUnknownSameDayUseConflictsEvenWhenDayEndIsPositive()throws Exception {
        var b=browser();long user=owner(b);var input=input();input.put("initial_savings_balance",0);long id=loan(b,input);day(20);
        long saving=saving(user,id,"2026-03-02",100);payment(user,id,"2026-03-03",80,0);
        assertThat(patch(b,saving,"{\"saved_at\":\"2026-03-03\"}",key()).statusCode()).isEqualTo(409);
        assertThat(patch(b,saving,"{\"saved_at\":\"2026-03-01\"}",key()).statusCode()).isEqualTo(200);
    }
    @Test void unrelatedEarlierSameDayUseDoesNotBlockLaterSafeModification()throws Exception {
        var b=browser();long user=owner(b);var input=input();input.put("initial_savings_balance",0);long id=loan(b,input);day(20);
        saving(user,id,"2026-03-02",100);payment(user,id,"2026-03-02",80,0);long saving=saving(user,id,"2026-03-10",100);
        assertThat(patch(b,saving,"{\"amount\":50}",key()).statusCode()).isEqualTo(200);
    }
    @Test void replayIsIndependentOfLaterStatusAndConflictingPayloadDoesNotWriteAgain()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),saving=saving(user,id,"2026-03-01",100);clearInvocations(budgets);String key=key();
        var first=patch(b,saving,"{\"amount\":200}",key);assertThat(first.statusCode()).isEqualTo(200);long rev=revision(user);
        jdbc.update("UPDATE loans SET status='DELETED' WHERE id=?",id);day(20);
        var replay=patch(b,saving,"{\"amount\":200}",key);assertThat(replay.statusCode()).isEqualTo(200);assertThat(json(replay)).isEqualTo(json(first));
        assertThat(patch(b,saving,"{\"amount\":201}",key).statusCode()).isEqualTo(409);assertThat(revision(user)).isEqualTo(rev);verify(budgets).recalculateFrom(eq(user),any());
    }
    @Test void keyScopeIncludesSavingId()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),a=saving(user,id,"2026-03-01",100),c=saving(user,id,"2026-03-01",100);String key=key();
        assertThat(patch(b,a,"{\"amount\":200}",key).statusCode()).isEqualTo(200);assertThat(patch(b,c,"{\"amount\":200}",key).statusCode()).isEqualTo(200);
        assertThat(count("idempotency_records",user)).isEqualTo(2);
    }
    @Test void recalculationFailureRollsBackSourceRevisionAndResponseAndAllowsRetry()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),saving=saving(user,id,"2026-03-01",100);long rev=revision(user);String key=key();
        doThrow(new IllegalStateException("test rollback")).when(budgets).recalculateFrom(eq(user),any());
        assertThat(patch(b,saving,"{\"amount\":200}",key).statusCode()).isEqualTo(500);
        assertThat(list(b,id,"").path("results").get(0).path("amount").longValue()).isEqualTo(100);assertThat(revision(user)).isEqualTo(rev);assertThat(count("idempotency_records",user)).isZero();
        doNothing().when(budgets).recalculateFrom(eq(user),any());assertThat(patch(b,saving,"{\"amount\":200}",key).statusCode()).isEqualTo(200);
    }
    @Test void failureAfterRevisionBumpRollsBackSourceAndAllEffects()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),saving=saving(user,id,"2026-03-01",100);long rev=revision(user);
        doAnswer(call->{jdbc.update("UPDATE loans SET initial_plan_snapshot='{}'::jsonb WHERE id=?",id);return null;}).when(budgets).recalculateFrom(eq(user),any());
        assertThat(patch(b,saving,"{\"amount\":200}",key()).statusCode()).isEqualTo(503);
        assertThat(revision(user)).isEqualTo(rev);assertThat(count("idempotency_records",user)).isZero();assertThat(sources.loan(user,id).initialPlan().version()).isEqualTo(1);
        assertThat(list(b,id,"").path("results").get(0).path("amount").longValue()).isEqualTo(100);
    }
    @Test void concurrentSameKeyUpdatesOnceAndNewKeyForSameValueDoesNotChangeAgain()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),saving=saving(user,id,"2026-03-01",100);clearInvocations(budgets);long rev=revision(user);String key=key();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<HttpResponse<String>> task=()->{start.await();return patch(b,saving,"{\"amount\":200}",key);};
            var a=pool.submit(task);var c=pool.submit(task);start.countDown();var first=a.get(15,TimeUnit.SECONDS);var second=c.get(15,TimeUnit.SECONDS);
            assertThat(first.statusCode()).isEqualTo(200);assertThat(json(second)).isEqualTo(json(first));
        }
        assertThat(revision(user)).isEqualTo(rev+1);verify(budgets).recalculateFrom(eq(user),any());
        assertThat(patch(b,saving,"{\"amount\":200}",key()).statusCode()).isEqualTo(200);assertThat(revision(user)).isEqualTo(rev+1);
    }
    @Test void concurrentDistinctChangesAreSerializedWithoutLostRevision()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),saving=saving(user,id,"2026-03-01",100);clearInvocations(budgets);long rev=revision(user);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return patch(b,saving,"{\"amount\":200}",key()).statusCode();});
            var c=pool.submit(()->{start.await();return patch(b,saving,"{\"amount\":300}",key()).statusCode();});
            start.countDown();assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(200);assertThat(c.get(15,TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(revision(user)).isEqualTo(rev+2);verify(budgets,times(2)).recalculateFrom(eq(user),any());
        assertThat(count("repayment_savings",user)).isEqualTo(1);assertThat(count("idempotency_records",user)).isEqualTo(2);
        assertThat(list(b,id,"").path("results").get(0).path("amount").longValue()).isIn(200L,300L);
    }
    @Test void nonBulletAndInvalidPathUseValidationAndExactRoute()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,new LoanPreviewTest().body()),saving=saving(user,id,"2026-03-01",100);
        assertThat(patch(b,saving,"{\"amount\":200}",key()).statusCode()).isEqualTo(400);
        var noSlash=b.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repayment-savings/"+saving))
            .header("Origin","http://localhost:5173").header("Content-Type","application/json").header("Idempotency-Key",key())
            .method("PATCH",HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(noSlash.statusCode()).isEqualTo(404);
    }
    @Test void historicalPeakOverflowIsConflictEvenIfFinalAssetsWouldFit()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());day(20);long saving=saving(user,id,"2026-03-01",1);
        saving(user,id,"2026-03-02",Long.MAX_VALUE-2000001);payment(user,id,"2026-03-03",100,0);
        assertThat(patch(b,saving,"{\"amount\":2}",key()).statusCode()).isEqualTo(409);
    }
}
