package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "loanrun.demo-mode=true","server.servlet.session.cookie.secure=false","loanrun.reports.worker-enabled=false"})
@Import(DashboardPortIntegrationTest.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DashboardPortIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-03-10T01:00:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}
        public Clock withZone(ZoneId z){return Clock.fixed(value,z);}
        public Instant instant(){return value;}
    }
    @TestConfiguration static class Config {@Bean @Primary TestClock dashClock(){return new TestClock();}}

    @LocalServerPort int port;
    @Autowired DashboardQueryPort dashboardPort;
    @Autowired RevisionPort revisions;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;

    @BeforeEach void reset(){clock.value=Instant.parse("2026-03-10T01:00:00Z");}

    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient c,String method,String path,String body,String key)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
            .header("Origin","http://localhost:5173").header("Content-Type","application/json");
        if(key!=null)b.header("Idempotency-Key",key);
        return c.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    long init(HttpClient c)throws Exception{return json(call(c,"GET","/api/users/me/",null,null)).path("data").path("user_id").asLong();}

    long createLoan(HttpClient c,String name,boolean bullet,int paymentDay,long original,long remaining)throws Exception {
        var body=new LoanPreviewTest().body();
        body.put("product_name",name);
        body.put("fee_exempt",true);
        body.put("original_principal",original);
        body.put("remaining_principal",remaining);
        body.put("start_date","2026-03-01");
        body.put("maturity_date","2027-03-"+String.format("%02d",paymentDay));
        body.put("payment_day",paymentDay);
        if(bullet){body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);}
        var r=call(c,"POST","/api/loans/",mapper.writeValueAsString(body),null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json(r).path("data").path("loan_id").asLong();
    }

    @Test void emptyUserDashboardReturnsRealEmptyStateWithoutBumpingRevision()throws Exception {
        var c=browser();long u=init(c),rev=revisions.current(u);
        var r=call(c,"GET","/api/dashboard/",null,null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var data=json(r).path("data");
        assertThat(data.path("loans")).isEmpty();
        assertThat(data.path("total_remaining_principal").asLong()).isZero();
        assertThat(data.path("overall_progress_percent").isNull()).isTrue();
        assertThat(data.path("estimated_payoff_date").isNull()).isTrue();
        assertThat(data.path("next_payment").isNull()).isTrue();
        assertThat(data.path("monthly_checklist")).isEmpty();
        assertThat(data.path("monthly_summary").path("total_count").asInt()).isZero();
        assertThat(data.path("risk_summary").path("locked").asBoolean()).isTrue();
        assertThat(data.path("report_status").path("locked").asBoolean()).isTrue();
        assertThat(revisions.current(u)).isEqualTo(rev);
    }

    @Test void householdAndSelectedScopesHonorOverridesSavingsPaidDeletedAndProRisk()throws Exception {
        var c=browser();long u=init(c);
        assertThat(call(c,"PUT","/api/budgets/profile/",
            "{\"income_type\":\"REGULAR\",\"monthly_income\":5000000,\"payday\":5,\"fixed_essential_expenses\":1000000,\"variable_essential_expenses\":500000,\"discretionary_expenses\":500000,\"savings\":200000,\"emergency_fund\":1000000}",
            null).statusCode()).isEqualTo(200);

        // Loan 1 (installment, due 25th): original 12M, remaining 6M -> 50% progress
        long inst=createLoan(c,"신용대출A",false,25,12000000,6000000);
        // Loan 2 (BULLET, due 15th): original 12M, remaining 12M -> 0% progress, initial savings 2M
        long bullet=createLoan(c,"만기대출B",true,15,12000000,12000000);
        // Loan 3 (will be DELETED): excluded from selector & 404 when selected
        long deleted=createLoan(c,"삭제대출C",false,20,1000000,1000000);
        assertThat(call(c,"DELETE","/api/loans/"+deleted+"/",null,null).statusCode()).isEqualTo(200);
        // Loan 4 (PAID): remains in selector, selectable with 100% progress & null next_payment (future dues removed on payoff)
        long paid=createLoan(c,"완납대출D",false,28,2000000,2000000);
        jdbc.update("DELETE FROM due_items WHERE loan_id=?",paid);
        jdbc.update("UPDATE loans SET status='PAID', remaining_principal=0 WHERE id=?",paid);

        // Add current-month override: 150k extra for inst, 300k saving for bullet
        assertThat(call(c,"PUT","/api/budgets/2026-03/allocation/",
            "{\"allocations\":[{\"loan_id\":"+inst+",\"allocation_type\":\"EXTRA_PAYMENT\",\"amount\":150000},{\"loan_id\":"+bullet+",\"allocation_type\":\"REPAYMENT_SAVING\",\"amount\":300000}],\"reserve_amount\":0}",
            "alloc-1").statusCode()).isEqualTo(200);

        // Record actual saving deposit (100k) on bullet
        assertThat(call(c,"POST","/api/repayment-savings/",
            "{\"loan_id\":"+bullet+",\"saved_at\":\"2026-03-10\",\"amount\":100000}",
            "save-1").statusCode()).isEqualTo(201);

        // 1) Switch to PRO and verify full end-to-end GET /api/dashboard/ with real risk + payoff + overrides + savings
        assertThat(call(c,"PATCH","/api/settings/subscription/","{\"plan_type\":\"PRO\"}",null).statusCode()).isEqualTo(200);
        long proRev=revisions.current(u);

        var proResp=call(c,"GET","/api/dashboard/",null,null);
        assertThat(proResp.statusCode()).as(proResp.body()).isEqualTo(200);
        var proHome=json(proResp).path("data");
        assertThat(proHome.path("loans")).hasSize(3);
        assertThat(proHome.path("loans").findValuesAsText("product_name")).containsExactly("신용대출A","만기대출B","완납대출D");
        assertThat(proHome.path("total_remaining_principal").asLong()).isEqualTo(18000000);
        assertThat(proHome.path("overall_progress_percent").decimalValue()).isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(proHome.path("estimated_payoff_date").asText()).isNotBlank();
        assertThat(proHome.path("risk_summary").path("locked").asBoolean()).isFalse();
        assertThat(proHome.path("risk_summary").path("available_cash").isNumber()).isTrue();
        assertThat(proHome.path("report_status").path("status").asText()).isEqualTo("NOT_GENERATED");
        assertThat(revisions.current(u)).isEqualTo(proRev);

        // 2) Switch back to FREE and record intra-cycle EXTRA payments (including BULLET savings-funded repayment)
        assertThat(call(c,"PATCH","/api/settings/subscription/","{\"plan_type\":\"FREE\"}",null).statusCode()).isEqualTo(200);
        assertThat(call(c,"POST","/api/payments/",
            "{\"loan_id\":"+inst+",\"paid_at\":\"2026-03-10\",\"payment_type\":\"EXTRA\",\"amount\":50000}",
            "pay-1").statusCode()).isEqualTo(201);
        assertThat(call(c,"POST","/api/payments/",
            "{\"loan_id\":"+bullet+",\"paid_at\":\"2026-03-10\",\"payment_type\":\"EXTRA\",\"amount\":400000,\"savings_used_amount\":300000}",
            "pay-2").statusCode()).isEqualTo(201);
        long rev=revisions.current(u);

        var homeResp=call(c,"GET","/api/dashboard/",null,null);
        assertThat(homeResp.statusCode()).as(homeResp.body()).isEqualTo(200);
        var home=json(homeResp).path("data");

        // Selector contains ACTIVE + PAID (3 loans), never DELETED
        assertThat(home.path("loans")).hasSize(3);

        // Remaining principal: (6M - 50k) + (12M - 400k) = 5,950,000 + 11,600,000 = 17,550,000
        assertThat(home.path("total_remaining_principal").asLong()).isEqualTo(17550000);
        // Active overall progress: (24M - 17.55M) / 24M * 100 = 26.88%
        assertThat(home.path("overall_progress_percent").decimalValue()).isEqualByComparingTo(new BigDecimal("26.88"));
        // Frozen due after intra-cycle extra keeps unknown payoff forecast null rather than failing
        assertThat(home.path("estimated_payoff_date").isNull()).isTrue();

        // Checklist sorted by dueDate: bullet (Mar 15) before inst (Mar 25)
        assertThat(home.path("monthly_checklist")).hasSize(2);
        assertThat(home.path("monthly_checklist").get(0).path("loan_id").asLong()).isEqualTo(bullet);
        assertThat(home.path("monthly_checklist").get(1).path("loan_id").asLong()).isEqualTo(inst);
        assertThat(home.path("next_payment").path("loan_id").asLong()).isEqualTo(bullet);

        // Override & saving/extra reflection on checklist items
        var instDue=home.path("monthly_checklist").get(1);
        assertThat(instDue.path("planned_extra_amount").asLong()).isEqualTo(150000);
        assertThat(instDue.path("paid_extra_amount").asLong()).isEqualTo(50000);
        assertThat(instDue.path("remaining_extra_amount").asLong()).isEqualTo(100000);
        assertThat(instDue.path("total_planned_amount").asLong()).isEqualTo(instDue.path("required_amount").asLong()+150000);

        var bulletDue=home.path("monthly_checklist").get(0);
        assertThat(bulletDue.path("planned_saving_amount").asLong()).isEqualTo(300000);
        assertThat(bulletDue.path("actual_saving_amount").asLong()).isEqualTo(100000);

        // Monthly summary: actual_repayment excludes reused savings (50k + (400k - 300k) = 150k), actual_saving is separate (100k)
        var summary=home.path("monthly_summary");
        assertThat(summary.path("actual_repayment").asLong()).isEqualTo(150000);
        assertThat(summary.path("actual_extra_amount").asLong()).isEqualTo(450000);
        assertThat(summary.path("actual_saving_amount").asLong()).isEqualTo(100000);
        assertThat(summary.path("planned_extra_amount").asLong()).isEqualTo(150000);
        assertThat(summary.path("planned_saving_amount").asLong()).isEqualTo(300000);
        assertThat(summary.path("total_count").asInt()).isEqualTo(2);
        assertThat(summary.path("completed_count").asInt()).isZero();
        assertThat(revisions.current(u)).isEqualTo(rev);

        // Selected PAID loan keeps complete selector, shows 100.00 progress, 0 remaining, null next_payment
        var paidResp=call(c,"GET","/api/dashboard/?loan_id="+paid,null,null);
        assertThat(paidResp.statusCode()).isEqualTo(200);
        var paidData=json(paidResp).path("data");
        assertThat(paidData.path("loans")).hasSize(3);
        assertThat(paidData.path("total_remaining_principal").asLong()).isZero();
        assertThat(paidData.path("overall_progress_percent").decimalValue()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(paidData.path("next_payment").isNull()).isTrue();

        // Deleted loan and foreign browser get 404
        assertThat(call(c,"GET","/api/dashboard/?loan_id="+deleted,null,null).statusCode()).isEqualTo(404);
        var other=browser();init(other);
        assertThat(call(other,"GET","/api/dashboard/?loan_id="+inst,null,null).statusCode()).isEqualTo(404);

        // Past/future asOf rejected with 503
        assertThatThrownBy(()->dashboardPort.snapshot(u,null,LocalDate.of(2026,3,11)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
}
