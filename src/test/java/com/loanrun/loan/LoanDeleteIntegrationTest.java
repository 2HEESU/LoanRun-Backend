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
@Import(LoanDeleteIntegrationTest.Config.class)
class LoanDeleteIntegrationTest {
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

    HttpResponse<String> delete(HttpClient browser,long id,String body,String key)throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/loans/"+id+"/")).header("Origin","http://localhost:5173");
        if(key!=null)r.header("Idempotency-Key",key);
        if(body==null)r.DELETE();else r.header("Content-Type","application/json").method("DELETE",HttpRequest.BodyPublishers.ofString(body));
        return browser.send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
    long count(String table,long user){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE user_id=?",Long.class,user);}
    long revision(long user){return jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,user);}
    String key(){return UUID.randomUUID().toString();}
    @Test void softDeletionPreservesBalancesSavingsHistoriesDuesAndPlanWithoutRequiringKey()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());saving(user,id,"2026-03-01",100);clearInvocations(budgets);
        var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));long rev=revision(user);day(2);
        var r=delete(b,id,null,null);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var expected=mapper.readTree("{\"success\":true,\"code\":\"LOAN_DELETE_SUCCESS\",\"message\":\"대출을 삭제했습니다.\",\"data\":{\"loan_id\":"+id+",\"deleted\":true,\"status\":\"DELETED\"}}");
        assertThat(json(r)).isEqualTo(expected);
        var after=sources.loanSnapshot(user,id,LocalDate.of(2026,3,2));var old=before.loans().getFirst();var current=after.loans().getFirst();
        assertThat(current.storedStatus().name()).isEqualTo("DELETED");assertThat(current.updatedAt()).isEqualTo(clock.instant());
        assertThat(current.storedRemainingPrincipal()).isEqualTo(old.storedRemainingPrincipal());assertThat(current.annualRate()).isEqualTo(old.annualRate());
        assertThat(current.initialSavings()).isEqualTo(old.initialSavings());assertThat(current.initialPlan()).isEqualTo(old.initialPlan());
        assertThat(current.createdAt()).isEqualTo(old.createdAt());assertThat(after.balances()).isEqualTo(before.balances());assertThat(after.rates()).isEqualTo(before.rates());
        assertThat(after.savings()).isEqualTo(before.savings());assertThat(after.dues()).isEqualTo(before.dues());assertThat(after.payments()).isEmpty();assertThat(after.allocations()).isEmpty();
        assertThat(count("loans",user)).isEqualTo(1);assertThat(revision(user)).isEqualTo(rev+1);assertThat(count("idempotency_records",user)).isZero();
        verify(budgets).recalculateFrom(user,YearMonth.of(2026,3));
        var active=json(get(b,"/api/loans/")).path("data");assertThat(active.path("count").intValue()).isZero();assertThat(active.path("total_remaining_principal").longValue()).isZero();
        assertThat(json(get(b,"/api/loans/?status=ALL")).path("data").path("count").intValue()).isZero();
        assertThat(get(b,"/api/loans/"+id+"/rate-history/").statusCode()).isEqualTo(404);
        assertThat(list(b,id,"").path("savings_balance").longValue()).isEqualTo(2000100);assertThat(list(b,id,"").path("results").get(0).path("editable").booleanValue()).isFalse();
        assertThat(delete(b,id,null,null).statusCode()).isEqualTo(404);assertThat(revision(user)).isEqualTo(rev+1);verify(budgets,times(1)).recalculateFrom(eq(user),any());
    }
    @ParameterizedTest @ValueSource(strings={"EQUAL_PAYMENT","EQUAL_PRINCIPAL","GRACE_INSTALLMENT","BULLET"})
    void supportsAllMethodsWithoutChangingAmount(String method)throws Exception {
        var b=browser();long user=owner(b);var body=new LoanPreviewTest().body();body.put("repayment_method",method);
        if(method.equals("BULLET"))body.putNull("prepayment_policy");
        if(method.equals("GRACE_INSTALLMENT")){body.put("grace_months",15);body.put("post_grace_method","EQUAL_PRINCIPAL");}
        long id=loan(b,body);assertThat(delete(b,id,null,null).statusCode()).isEqualTo(200);assertThat(sources.loan(user,id).storedRemainingPrincipal()).isEqualTo(12000000);
    }
    @Test void ownAndForeignLoanPaymentsDoNotBlockUnrelatedTarget()throws Exception {
        var a=browser();var b=browser();long ua=owner(a),ub=owner(b),target=loan(a,input()),other=loan(a,input()),foreign=loan(b,input());
        payment(ua,other,"2026-03-01",0,10);payment(ub,foreign,"2026-03-01",0,10);
        assertThat(delete(a,target,null,null).statusCode()).isEqualTo(200);assertThat(sources.loan(ua,other).storedStatus().name()).isEqualTo("ACTIVE");
    }
    @ParameterizedTest @ValueSource(strings={"REGULAR","EXTRA"})
    void anyActualPaymentConflictsIncludingInterestOnlyOrExtra(String type)throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());
        long payment=payment(user,id,"2026-03-01",type.equals("EXTRA")?1:0,type.equals("REGULAR")?1:0);
        if(type.equals("EXTRA"))jdbc.update("UPDATE payments SET payment_type='EXTRA',principal_amount=0,extra_amount=1 WHERE id=?",payment);
        clearInvocations(budgets);long rev=revision(user);
        var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));var r=delete(b,id,null,key());
        assertThat(r.statusCode()).isEqualTo(409);assertThat(json(r).path("code").asText()).isEqualTo("LOAN_HAS_PAYMENTS");
        assertThat(json(r).path("message").asText()).isEqualTo("상환 내역이 있는 대출은 삭제할 수 없습니다.");
        assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);assertThat(revision(user)).isEqualTo(rev);assertThat(count("idempotency_records",user)).isZero();verifyNoInteractions(budgets);
    }
    @Test void paidLoanWithoutPaymentsCanBeArchivedButIsNotConvertedIntoNewRepayment()throws Exception {
        var b=browser();long user=owner(b);var body=input();body.put("remaining_principal",0);long id=loan(b,body);
        assertThat(sources.loan(user,id).storedStatus().name()).isEqualTo("PAID");assertThat(delete(b,id,null,null).statusCode()).isEqualTo(200);
        assertThat(count("payments",user)).isZero();assertThat(sources.loan(user,id).storedRemainingPrincipal()).isZero();
    }
    @Test void foreignMissingAndDeletedResourcesHaveIdentical404()throws Exception {
        var a=browser();var b=browser();long id=loan(a,input());var foreign=delete(b,id,null,key());var missing=delete(b,Long.MAX_VALUE,null,key());
        assertThat(foreign.statusCode()).isEqualTo(404);assertThat(json(foreign)).isEqualTo(json(missing));assertThat(json(foreign).path("code").asText()).isEqualTo("LOAN_NOT_FOUND");
        assertThat(delete(a,id,null,null).statusCode()).isEqualTo(200);assertThat(json(delete(a,id,null,null))).isEqualTo(json(missing));
    }
    @Test void providedKeyReplaysAfterDeletionAndDateChangeAndRejectsChangedBody()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());clearInvocations(budgets);String key=key();
        var first=delete(b,id,null,key);assertThat(first.statusCode()).isEqualTo(200);long rev=revision(user);day(20);
        var replay=delete(b,id,null,key);assertThat(replay.statusCode()).isEqualTo(200);assertThat(json(replay)).isEqualTo(json(first));
        var changed=delete(b,id,"{}",key);assertThat(changed.statusCode()).isEqualTo(409);assertThat(json(changed).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(delete(b,id,null,key()).statusCode()).isEqualTo(404);assertThat(revision(user)).isEqualTo(rev);verify(budgets).recalculateFrom(eq(user),any());
    }
    @Test void invalidOptionalKeyOrBodyDoesNotChangeState()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());long rev=revision(user);
        for(String key:List.of("","x".repeat(129)))assertThat(delete(b,id,null,key).statusCode()).isEqualTo(400);
        for(String body:List.of("{}","null","[]"," "))assertThat(delete(b,id,body,null).statusCode()).isEqualTo(400);
        assertThat(revision(user)).isEqualTo(rev);assertThat(sources.loan(user,id).storedStatus().name()).isEqualTo("ACTIVE");assertThat(count("idempotency_records",user)).isZero();
    }
    @Test void recalculationFailureRollsBackStatusAndKeyAndRetryWorks()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());var before=jdbc.queryForMap("SELECT * FROM loans WHERE id=?",id);long rev=revision(user);String key=key();
        doAnswer(call->{assertThat(jdbc.queryForObject("SELECT status FROM loans WHERE id=?",String.class,id)).isEqualTo("DELETED");throw new IllegalStateException("test rollback");}).when(budgets).recalculateFrom(eq(user),any());
        assertThat(delete(b,id,null,key).statusCode()).isEqualTo(500);assertThat(jdbc.queryForMap("SELECT * FROM loans WHERE id=?",id)).isEqualTo(before);
        assertThat(revision(user)).isEqualTo(rev);assertThat(count("idempotency_records",user)).isZero();
        doNothing().when(budgets).recalculateFrom(eq(user),any());assertThat(delete(b,id,null,key).statusCode()).isEqualTo(200);
    }
    @Test void deletionRecalculatesCurrentKstMonthRatherThanRegistrationMonth()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());clearInvocations(budgets);clock.value=Instant.parse("2026-03-31T15:00:00Z");
        assertThat(delete(b,id,null,null).statusCode()).isEqualTo(200);verify(budgets).recalculateFrom(user,YearMonth.of(2026,4));
    }
    @Test void softDeletedLoanFreesAnActiveSlotWithoutRemovingOriginalLoan()throws Exception {
        var b=browser();long user=owner(b);var ids=new ArrayList<Long>();for(int i=0;i<7;i++)ids.add(loan(b,input()));
        assertThat(delete(b,ids.getFirst(),null,null).statusCode()).isEqualTo(200);long replacement=loan(b,input());
        assertThat(replacement).isNotIn(ids);assertThat(count("loans",user)).isEqualTo(8);
        assertThat(json(get(b,"/api/loans/")).path("data").path("count").intValue()).isEqualTo(7);
    }
    @Test void concurrentSameKeyReplaysOnceAndNoKeyCompetingDeletesHaveOne404()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());clearInvocations(budgets);long rev=revision(user);String key=key();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<HttpResponse<String>> task=()->{start.await();return delete(b,id,null,key);};
            var a=pool.submit(task);var c=pool.submit(task);start.countDown();var first=a.get(15,TimeUnit.SECONDS);var second=c.get(15,TimeUnit.SECONDS);
            assertThat(first.statusCode()).isEqualTo(200);assertThat(second.statusCode()).isEqualTo(200);assertThat(json(second)).isEqualTo(json(first));
        }
        assertThat(revision(user)).isEqualTo(rev+1);verify(budgets).recalculateFrom(eq(user),any());
        long other=loan(b,input());clearInvocations(budgets);long before=revision(user);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<Integer> task=()->{start.await();return delete(b,other,null,null).statusCode();};
            var a=pool.submit(task);var c=pool.submit(task);start.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),c.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,404);
        }
        assertThat(revision(user)).isEqualTo(before+1);verify(budgets).recalculateFrom(eq(user),any());
    }
    @Test void exactTrailingSlashIsRequired()throws Exception {
        var b=browser();long id=loan(b,input());var r=b.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/loans/"+id))
            .header("Origin","http://localhost:5173").DELETE().build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).isEqualTo(404);
    }
}
