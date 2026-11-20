package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.service.LoanRegistrationService;
import com.loanrun.repaymentplan.read.RegistrationPlanFallback;
import java.net.*;
import java.net.http.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Deliberately omits only the Team 1 versioned schema; the V2 ledger and independent V3.1 remain real. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.flyway.target=3.1","server.servlet.session.cookie.secure=false"})
@Import(RegistrationPlanCompatibilityIntegrationTest.Config.class)
class RegistrationPlanCompatibilityIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @TestConfiguration static class Config {@Bean @Primary Clock legacyClock(){return Clock.fixed(Instant.parse("2026-02-28T15:30:00Z"),ZoneId.of("Asia/Seoul"));}}
    @MockitoBean BudgetRecalculationPort budgets;
    @Autowired PlanQueryPort plans;
    @Autowired LoanRegistrationService registration;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @Test void registeredSavingReadSurvivesWithoutPretendingThatVersionedPlanHistoryIsAvailable()throws Exception {
        var browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var user=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/users/me/")).GET().build(),HttpResponse.BodyHandlers.ofString());
        long id=mapper.readTree(user.body()).path("data").path("user_id").longValue();
        var body=new LoanPreviewTest().body();body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);
        long loan=registration.create(id,body,null).body().path("data").path("loan_id").longValue();
        assertThat(((RegistrationPlanFallback)plans).registrationFallbackAllowed()).isTrue();
        assertThatThrownBy(()->plans.currentPlans(id,LocalDate.of(2026,3,1))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        var response=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repayment-savings/?loan_id="+loan)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("data").path("planned_saving_amount").longValue()).isEqualTo(1000000);
        assertThat(jdbc.queryForObject("SELECT to_regclass('repayment_plan_versions')",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT to_regclass('plan_application_events')",String.class)).isNull();
    }
}
