package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.BudgetRecalculationPort;
import com.loanrun.loan.read.LoanSourceRepository;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
@Import(LoanListIntegrationTest.Config.class)
class LoanListIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-02-28T15:30:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}
        public Clock withZone(ZoneId zone){return Clock.fixed(value,zone);}
        public Instant instant(){return value;}
    }
    @TestConfiguration static class Config {@Bean @Primary TestClock listClock(){return new TestClock();}}
    // Only registration fixture setup replaces the pending Team 2 recalculation provider.
    @MockitoBean BudgetRecalculationPort budget;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired LoanSourceRepository sources;
    @LocalServerPort int port;
    @BeforeEach void reset(){clock.value=Instant.parse("2026-02-28T15:30:00Z");}
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> get(HttpClient b,String path)throws Exception{return b.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    JsonNode list(HttpClient b,String query)throws Exception{var r=get(b,"/api/loans/"+query);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return json(r).path("data");}
    long owner(HttpClient b)throws Exception{return json(get(b,"/api/users/me/")).path("data").path("user_id").longValue();}
    long loan(HttpClient b,ObjectNode body)throws Exception {
        var r=b.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/loans/"))
            .header("Origin","http://localhost:5173").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);return json(r).path("data").path("loan_id").longValue();
    }
    ObjectNode body()throws Exception{return new LoanPreviewTest().body();}
    void saving(long user,long loan,String date,long amount){jdbc.update("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP)",user,loan,LocalDate.parse(date),amount);}
    long payment(long user,long loan,long principal,long interest,long extra,long used){return jdbc.queryForObject("""
        INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,interest_amount,extra_amount,fee_amount,savings_used_amount,created_at)
        VALUES (?,?,'2026-03-01','REGULAR',?,?,?,?,0,?,CURRENT_TIMESTAMP) RETURNING id
        """,Long.class,user,loan,principal+interest+extra,principal,interest,extra,used);}
    void payDue(long user,long loan,long amount){long payment=payment(user,loan,amount,0,0,0);
        jdbc.update("INSERT INTO payment_allocations(user_id,loan_id,payment_id,due_item_id,principal_amount,interest_amount,fee_amount) SELECT ?,?,?,id,?,0,0 FROM due_items WHERE user_id=? AND loan_id=?",user,loan,payment,amount,user,loan);}

    @Test void emptySessionReturnsRealEmptyCollectionAndUnknownPayoff()throws Exception {
        var b=browser();var r=get(b,"/api/loans/");assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).path("code").asText()).isEqualTo("LOAN_LIST_SUCCESS");
        var d=json(r).path("data");assertThat(d.path("loans").size()).isZero();assertThat(d.path("count").intValue()).isZero();
        assertThat(d.path("total_remaining_principal").longValue()).isZero();assertThat(d.path("total_monthly_due").longValue()).isZero();
        assertThat(d.path("estimated_payoff_date").isNull()).isTrue();
    }
    @Test void defaultFilterOwnerScopeAndCommonLoanFields()throws Exception {
        var a=browser();var b=browser();long user=owner(a);long id=loan(a,body());loan(b,body());
        var d=list(a,"");var l=d.path("loans").get(0);
        assertThat(d.path("count").intValue()).isEqualTo(1);assertThat(l.path("loan_id").longValue()).isEqualTo(id);
        assertThat(l.path("as_of").asText()).isEqualTo("2026-03-01");assertThat(l.path("rate_updated_at").asText()).isEqualTo("2026-03-01T00:30:00+09:00");
        assertThat(l.path("estimated_monthly_payment").longValue()).isEqualTo(1260000);
        assertThat(d.path("total_monthly_due").longValue()).isEqualTo(1260000);
        assertThat(l.path("product_id").isNull()).isTrue();assertThat(l.path("contractual_monthly_payment").isNull()).isTrue();
        assertThat(l.path("prepayment_fee_rate").isNull()).isTrue();assertThat(l.path("fee_exempt").isNull()).isTrue();
        assertThat(l.path("rate_link_consent").booleanValue()).isFalse();assertThat(l.path("rate_sync_status").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(l.path("snapshot_principal").longValue()).isEqualTo(12000000);assertThat(l.path("snapshot_date").asText()).isEqualTo("2026-03-01");
        assertThat(sources.loan(user,id).storedRemainingPrincipal()).isEqualTo(12000000);
    }
    @Test void paidAllDeletedAndForeignRowsUseStatusAndActiveOnlyTotals()throws Exception {
        var b=browser();long user=owner(b),active=loan(b,body()),paid=loan(b,body()),deleted=loan(b,body());
        jdbc.update("UPDATE loans SET status='PAID',remaining_principal=0 WHERE id=?",paid);
        jdbc.update("UPDATE loans SET status='DELETED',initial_plan_snapshot='{}'::jsonb WHERE id=?",deleted);
        assertThat(list(b,"").path("count").intValue()).isEqualTo(1);
        var all=list(b,"?status=ALL");assertThat(all.path("count").intValue()).isEqualTo(2);
        assertThat(all.path("total_remaining_principal").longValue()).isEqualTo(12000000);
        assertThat(all.path("total_monthly_due").longValue()).isEqualTo(1260000);
        var p=list(b,"?status=PAID");assertThat(p.path("count").intValue()).isEqualTo(1);
        assertThat(p.path("loans").get(0).path("loan_id").longValue()).isEqualTo(paid);
        assertThat(p.path("loans").get(0).path("estimated_monthly_payment").longValue()).isZero();
        assertThat(p.path("total_monthly_due").longValue()).isZero();assertThat(p.path("total_remaining_principal").longValue()).isZero();
        assertThat(all.path("loans").get(0).path("loan_id").longValue()).isEqualTo(active);
    }
    @ParameterizedTest @ValueSource(strings={"DELETED","active","UNKNOWN",""})
    void invalidStatusUsesSpecifiedError(String status)throws Exception {
        var r=get(browser(),"/api/loans/?status="+status);assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(json(r).path("errors").path("status").get(0).asText()).isEqualTo("입력값이 올바르지 않습니다.");
    }
    @Test void contractualZeroIsNotMissingAndDoesNotRemoveEstimatedInterest()throws Exception {
        var b=browser();var input=body();input.put("contractual_monthly_payment",0);input.put("fee_exempt",false);loan(b,input);
        var d=list(b,"");var l=d.path("loans").get(0);
        assertThat(l.path("contractual_monthly_payment").isNull()).isFalse();assertThat(l.path("contractual_monthly_payment").longValue()).isZero();
        assertThat(d.path("total_monthly_due").longValue()).isZero();assertThat(l.path("estimated_monthly_payment").longValue()).isEqualTo(60000);
        assertThat(l.path("fee_exempt").booleanValue()).isFalse();
    }
    @Test void currentBalanceIsNotReducedAgainAndFrozenInterestSurvivesExtraPayment()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,body());payment(user,id,0,0,1000000,0);
        jdbc.update("UPDATE loans SET remaining_principal=11000000 WHERE id=?",id);
        var l=list(b,"").path("loans").get(0);
        assertThat(l.path("remaining_principal").longValue()).isEqualTo(11000000);
        assertThat(l.path("estimated_monthly_payment").longValue()).isEqualTo(1260000);
        assertThat(l.path("paid_principal").longValue()).isEqualTo(1000000);
        assertThat(l.path("progress_percent").decimalValue()).isEqualByComparingTo("8.33");
    }
    @Test void bulletAssetsUseActualDatesAndNeverReducePrincipal()throws Exception {
        var b=browser();long user=owner(b);var input=body();input.put("repayment_method","BULLET");input.putNull("prepayment_policy");input.put("initial_savings_balance",2000000);
        long id=loan(b,input);saving(user,id,"2026-03-01",500000);saving(user,id,"2026-03-02",900000);payment(user,id,200000,0,0,200000);
        jdbc.update("UPDATE loans SET remaining_principal=11800000 WHERE id=?",id);
        var l=list(b,"").path("loans").get(0);
        assertThat(l.path("savings_balance").longValue()).isEqualTo(2300000);
        assertThat(l.path("remaining_principal").longValue()).isEqualTo(11800000);
        assertThat(l.path("required_monthly_saving").longValue()).isEqualTo(950000);
        assertThat(l.path("repayment_category").asText()).isEqualTo("LUMP_SUM");
    }
    @Test void completedBankCycleDoesNotCopyItsAmountIntoNextCycle()throws Exception {
        var b=browser();long user=owner(b);var input=body();input.put("contractual_monthly_payment",280000);long id=loan(b,input);
        payDue(user,id,280000);jdbc.update("UPDATE loans SET remaining_principal=11780000 WHERE id=?",id);
        var l=list(b,"").path("loans").get(0);
        assertThat(l.path("contractual_monthly_payment").isNull()).isTrue();
        assertThat(l.path("estimated_monthly_payment").longValue()).isEqualTo(1258900);
    }
    @Test void expiredZeroBankCycleAndMonthEndUseNextRealCalendarDate()throws Exception {
        var b=browser();long user=owner(b);var input=body();input.put("payment_day",31);input.put("contractual_monthly_payment",0);long id=loan(b,input);
        clock.value=Instant.parse("2026-03-31T15:00:00Z");
        var l=list(b,"").path("loans").get(0);assertThat(l.path("contractual_monthly_payment").isNull()).isTrue();
        assertThat(l.path("estimated_monthly_payment").longValue()).isEqualTo(1260000);
        assertThat(sources.snapshot(user,LocalDate.of(2026,4,1)).dues()).hasSize(1); // GET never manufactures April dues.
        assertThat(l.path("as_of").asText()).isEqualTo("2026-04-01");
    }
    @Test void allFourRepaymentMethodsReuseEngine()throws Exception {
        var b=browser();for(String method:List.of("EQUAL_PAYMENT","EQUAL_PRINCIPAL","GRACE_INSTALLMENT","BULLET")) {
            var input=body();input.put("repayment_method",method);
            if(method.equals("BULLET"))input.putNull("prepayment_policy");
            if(method.equals("GRACE_INSTALLMENT")){input.put("grace_months",15);input.put("post_grace_method","EQUAL_PRINCIPAL");}
            loan(b,input);
        }
        var d=list(b,"");assertThat(d.path("count").intValue()).isEqualTo(4);
        assertThat(d.path("loans").get(0).path("estimated_monthly_payment").longValue()).isEqualTo(1233247);
        assertThat(d.path("loans").get(2).path("estimated_monthly_payment").longValue()).isEqualTo(60000);
        assertThat(d.path("loans").get(3).path("estimated_monthly_payment").longValue()).isEqualTo(60000);
    }
    @Test void unavailableSourceFailsWithoutFakeEmptySuccess()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,body());jdbc.update("UPDATE loans SET initial_plan_snapshot='{}'::jsonb WHERE id=?",id);
        assertThat(get(b,"/api/loans/").statusCode()).isEqualTo(503);
        var other=browser();assertThat(list(other,"").path("count").intValue()).isZero();
    }
    @Test void negativeAssetSourceAndUnknownMaturedBalanceFailClosed()throws Exception {
        var b=browser();long user=owner(b);var input=body();input.put("repayment_method","BULLET");input.putNull("prepayment_policy");long id=loan(b,input);
        payment(user,id,1,0,0,1);assertThat(get(b,"/api/loans/").statusCode()).isEqualTo(503);
        var c=browser();long id2=loan(c,body());jdbc.update("DELETE FROM due_items WHERE loan_id=?",id2);
        clock.value=Instant.parse("2027-01-01T00:00:00Z");assertThat(get(c,"/api/loans/").statusCode()).isEqualTo(503);
    }
    @Test void nullableActualRateTimestampAndVariableManualState()throws Exception {
        var b=browser();long user=owner(b);var input=body();input.put("rate_type","VARIABLE");input.put("rate_reset_months",6);input.put("next_rate_reset_date","2026-07-25");long id=loan(b,input);
        jdbc.update("DELETE FROM loan_rate_histories WHERE user_id=? AND loan_id=?",user,id);
        var l=list(b,"").path("loans").get(0);assertThat(l.path("rate_updated_at").isNull()).isTrue();
        assertThat(l.path("rate_sync_status").asText()).isEqualTo("MANUAL_REQUIRED");
    }
    @Test void partialOrOverdueCycleKeepsItsFrozenBaseInsteadOfInventingCompletion()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,body());payDue(user,id,100000);
        jdbc.update("UPDATE loans SET remaining_principal=11900000 WHERE id=?",id);
        clock.value=Instant.parse("2026-04-01T00:00:00Z");
        var d=list(b,"");assertThat(d.path("loans").get(0).path("estimated_monthly_payment").longValue()).isEqualTo(1260000);
        assertThat(d.path("total_monthly_due").longValue()).isEqualTo(1260000);
    }
    @Test void aggregateOverflowIsUnavailableRatherThanWrappedNegativeMoney()throws Exception {
        var b=browser();long user=owner(b);loan(b,body());loan(b,body());
        jdbc.update("UPDATE loans SET original_principal=9000000000000000000,remaining_principal=9000000000000000000 WHERE user_id=?",user);
        assertThat(get(b,"/api/loans/").statusCode()).isEqualTo(503);
    }
    @Test void repeatedReadsDoNotChangeFinancialSourcesRevisionOrReports()throws Exception {
        var b=browser();long user=owner(b);loan(b,body());
        var state=sources.snapshot(user,LocalDate.of(2026,3,1));var before=jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",user);
        assertThat(list(b,"")).isEqualTo(list(b,""));assertThat(sources.snapshot(user,LocalDate.of(2026,3,1))).isEqualTo(state);
        assertThat(jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",user)).isEqualTo(before);
        assertThat(get(b,"/api/loans").statusCode()).isEqualTo(404);
    }
}
