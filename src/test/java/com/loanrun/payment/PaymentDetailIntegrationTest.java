package com.loanrun.payment;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.payment.repository.PaymentReadRepository;
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
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
@Import(PaymentDetailIntegrationTest.Config.class)
class PaymentDetailIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
    }
    @TestConfiguration static class Config {
        @Bean @Primary Clock fixed(){return Clock.fixed(Instant.parse("2026-03-31T01:00:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentReadRepository payments;
    @LocalServerPort int port;
    // Synthetic V2 source fixtures only. No runtime provider or test budget/query-port replacement.
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> get(HttpClient browser,String path)throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> response)throws Exception{return mapper.readTree(response.body());}
    long owner(HttpClient browser)throws Exception{return json(get(browser,"/api/users/me/")).path("data").path("user_id").longValue();}
    long loan(long owner){
        return jdbc.queryForObject("""
            INSERT INTO loans(user_id,institution_name,product_name,loan_form,loan_type,repayment_method,
                original_principal,remaining_principal,annual_rate,rate_type,start_date,maturity_date,payment_day,
                next_due_date,grace_months,prepayment_policy,prepayment_fee_method,initial_savings_balance,status,
                snapshot_date,snapshot_principal,initial_plan_snapshot,created_at,updated_at)
            VALUES (?,'가상은행','가상대출','GENERAL','MORTGAGE','BULLET',12000000,12000000,6,'FIXED',
                '2025-01-25','2026-12-25',25,'2026-03-25',0,NULL,'UNKNOWN',2000000,'ACTIVE',
                '2026-03-01',12000000,'{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id
            """,Long.class,owner);
    }
    long payment(long owner,long loan,String date,String kind,long principal,long extra,long interest,long fee,long used){
        return jdbc.queryForObject("""
            INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,
                fee_amount,savings_used_amount,memo,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
            """,Long.class,owner,loan,LocalDate.parse(date),kind,principal+extra+interest+fee,principal,extra,interest,fee,used,
                "조회 응답에 추가하지 않는 원본 메모",Timestamp.from(Instant.parse("2026-03-24T15:30:00Z")));
    }
    long due(long owner,long loan,String date){
        return jdbc.queryForObject("""
            INSERT INTO due_items(user_id,loan_id,due_date,principal_amount,interest_amount,fee_amount,required_amount,terms_revision,created_at)
            VALUES (?,?,?,500000,30000,100,530100,1,CURRENT_TIMESTAMP) RETURNING id
            """,Long.class,owner,loan,LocalDate.parse(date));
    }
    void allocate(long owner,long loan,long payment,long due,long principal,long interest,long fee){
        jdbc.update("""
            INSERT INTO payment_allocations(user_id,loan_id,payment_id,due_item_id,principal_amount,interest_amount,fee_amount)
            VALUES (?,?,?,?,?,?,?)
            """,owner,loan,payment,due,principal,interest,fee);
    }
    String path(long payment){return "/api/payments/"+payment+"/";}
    JsonNode detail(HttpClient browser,long id)throws Exception {
        var response=get(browser,path(id));assertThat(response.statusCode()).isEqualTo(200);return json(response).path("data");
    }
    void missing(HttpClient browser,long id)throws Exception {
        var response=get(browser,path(id));assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response)).isEqualTo(mapper.readTree("""
            {"success":false,"code":"PAYMENT_NOT_FOUND","message":"납부 기록을 찾을 수 없습니다.","errors":{}}
            """));
    }

    @Test void returnsExactStoredAmountsAllocationAndKstTimestampWithoutOtherRuntimePorts()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long payment=payment(owner,loan,"2026-03-25","REGULAR",250000,100000,30000,100,200000);
        long due=due(owner,loan,"2026-03-25");allocate(owner,loan,payment,due,250000,30000,100);
        var response=get(browser,path(payment));assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response)).isEqualTo(mapper.readTree("""
            {"success":true,"code":"PAYMENT_DETAIL_SUCCESS","message":"납부 기록 상세 정보를 조회했습니다.",
             "data":{"payment_id":%d,"loan_id":%d,"paid_at":"2026-03-25","amount":380100,"payment_type":"REGULAR",
              "due_item_id":%d,"principal_amount":250000,"interest_amount":30000,"extra_amount":100000,
              "created_at":"2026-03-25T00:30:00+09:00","fee_amount":100,"savings_used_amount":200000,
              "editable":true,"delay_days":0,"due_allocations":[{"due_item_id":%d,"required_paid_amount":280000,"delay_days":0}]}}
            """.formatted(payment,loan,due,due)));
    }
    @Test void multipleAllocationsUseOldestDueAsRepresentativeAndKeepEveryActualAllocation()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long payment=payment(owner,loan,"2026-03-27","REGULAR",500000,0,60000,100,0);
        long later=due(owner,loan,"2026-03-25"),earlier=due(owner,loan,"2026-03-10");
        allocate(owner,loan,payment,later,250000,30000,100);allocate(owner,loan,payment,earlier,250000,30000,0);
        var data=detail(browser,payment);
        assertThat(data.path("due_item_id").longValue()).isEqualTo(earlier);assertThat(data.path("delay_days").longValue()).isEqualTo(17);
        var allocations=data.path("due_allocations");assertThat(allocations.size()).isEqualTo(2);
        assertThat(allocations.get(0).path("due_item_id").longValue()).isEqualTo(earlier);
        assertThat(allocations.get(1).path("due_item_id").longValue()).isEqualTo(later);
        assertThat(allocations.get(1).path("delay_days").longValue()).isEqualTo(2);
        assertThat(allocations.get(1).path("required_paid_amount").longValue()).isEqualTo(280000); // Fee remains separate.
    }
    @Test void advancePaymentHasZeroDelayAndExtraHasNoInferredRegularDue()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long regular=payment(owner,loan,"2026-03-20","REGULAR",0,0,30000,0,0),due=due(owner,loan,"2026-03-25");
        allocate(owner,loan,regular,due,0,30000,0);
        assertThat(detail(browser,regular).path("delay_days").longValue()).isZero();
        long extra=payment(owner,loan,"2026-03-25","EXTRA",0,100000,0,100,50000);
        var data=detail(browser,extra);assertThat(data.path("payment_type").asText()).isEqualTo("EXTRA");
        assertThat(data.path("due_item_id").isNull()).isTrue();assertThat(data.path("delay_days").isNull()).isTrue();
        assertThat(data.path("due_allocations").isArray()).isTrue();assertThat(data.path("due_allocations").size()).isZero();
        assertThat(data.path("amount").longValue()).isEqualTo(100100);assertThat(data.path("savings_used_amount").longValue()).isEqualTo(50000);
    }
    @Test void missingStoredAllocationsRemainUnknownRatherThanMatchingPaymentToNearbyDue()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long payment=payment(owner,loan,"2026-03-25","REGULAR",100,0,0,0,0);due(owner,loan,"2026-03-25");
        var data=detail(browser,payment);assertThat(data.path("due_item_id").isNull()).isTrue();
        assertThat(data.path("delay_days").isNull()).isTrue();assertThat(data.path("due_allocations").size()).isZero();
    }
    @Test void foreignMissingAndNonpositiveIdsAreIndistinguishableAndLoanAllocationsDoNotLeak()throws Exception {
        var a=browser();var b=browser();long ownerA=owner(a),ownerB=owner(b),loanA=loan(ownerA),loanB=loan(ownerB);
        long paymentA=payment(ownerA,loanA,"2026-03-25","REGULAR",100,0,10,0,0);
        long paymentB=payment(ownerB,loanB,"2026-03-25","REGULAR",900,0,90,0,0);
        long dueA=due(ownerA,loanA,"2026-03-25"),dueB=due(ownerB,loanB,"2026-03-25");
        allocate(ownerA,loanA,paymentA,dueA,100,10,0);allocate(ownerB,loanB,paymentB,dueB,900,90,0);
        missing(b,paymentA);missing(a,paymentB);missing(a,Long.MAX_VALUE);missing(a,0);missing(a,-1);
        var data=detail(a,paymentA);assertThat(data.path("amount").longValue()).isEqualTo(110);
        assertThat(data.path("due_allocations").size()).isEqualTo(1);
        assertThat(data.path("due_allocations").get(0).path("due_item_id").longValue()).isEqualTo(dueA);
    }
    @Test void deletedAndPaidLoansRetainActualHistoryAndDoNotChangeStoredAmounts()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),payment=payment(owner,loan,"2026-03-25","EXTRA",0,100,0,0,0);
        for(String status:List.of("PAID","DELETED")) {
            jdbc.update("UPDATE loans SET status=? WHERE user_id=? AND id=?",status,owner,loan);
            var data=detail(browser,payment);assertThat(data.path("loan_id").longValue()).isEqualTo(loan);
            assertThat(data.path("extra_amount").longValue()).isEqualTo(100);
        }
    }
    @Test void snapshotDateFreezesEarlierRecordsAndKeepsSameDayAndLaterRecordsEditable()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long before=payment(owner,loan,"2026-03-09","EXTRA",0,100,0,0,0);
        long same=payment(owner,loan,"2026-03-10","EXTRA",0,100,0,0,0);
        long after=payment(owner,loan,"2026-03-11","EXTRA",0,100,0,0,0);
        jdbc.update("UPDATE loans SET snapshot_date='2026-03-10' WHERE user_id=? AND id=?",owner,loan);
        assertThat(detail(browser,before).path("editable").booleanValue()).isFalse();
        assertThat(detail(browser,same).path("editable").booleanValue()).isTrue();
        assertThat(detail(browser,after).path("editable").booleanValue()).isTrue();
    }
    @Test void malformedPathUsesCommon400AndFinalSlashIsRequired()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),payment=payment(owner,loan,"2026-03-25","EXTRA",0,100,0,0,0);
        for(String value:List.of("abc","9223372036854775808")) {
            var response=get(browser,"/api/payments/"+value+"/");assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(json(response).has("data")).isFalse();assertThat(json(response).has("errors")).isTrue();
        }
        assertThat(get(browser,"/api/payments/"+payment).statusCode()).isEqualTo(404);
    }
    @Test void repeatedReadsDoNotRewriteSourcesRevisionOrUserTimestampAndSourceListIsImmutable()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),payment=payment(owner,loan,"2026-03-25","REGULAR",100,0,10,0,0);
        long due=due(owner,loan,"2026-03-25");allocate(owner,loan,payment,due,100,10,0);
        var userBefore=jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner);
        var loanBefore=jdbc.queryForMap("SELECT * FROM loans WHERE id=?",loan);
        var paymentBefore=jdbc.queryForMap("SELECT * FROM payments WHERE id=?",payment);
        var allocationsBefore=jdbc.queryForList("SELECT * FROM payment_allocations WHERE payment_id=?",payment);
        var first=detail(browser,payment);assertThat(detail(browser,payment)).isEqualTo(first);
        assertThat(jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner)).isEqualTo(userBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM loans WHERE id=?",loan)).isEqualTo(loanBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM payments WHERE id=?",payment)).isEqualTo(paymentBefore);
        assertThat(jdbc.queryForList("SELECT * FROM payment_allocations WHERE payment_id=?",payment)).isEqualTo(allocationsBefore);
        assertThatThrownBy(()->payments.detail(owner,payment).allocations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
