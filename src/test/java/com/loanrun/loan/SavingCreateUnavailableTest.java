package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.FeeMethod;
import com.loanrun.loan.domain.PreparedLoan;
import com.loanrun.loan.repository.LoanRegistrationRepository;
import com.loanrun.loan.service.LoanInputValidator;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Production wiring with no replacement budget bean. Fixture setup is not a public registration success claim. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
@Import(SavingCreateUnavailableTest.Config.class)
class SavingCreateUnavailableTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @TestConfiguration static class Config {@Bean @Primary Clock savingClock(){return Clock.fixed(Instant.parse("2026-02-28T15:30:00Z"),ZoneId.of("Asia/Seoul"));}}
    @Autowired ObjectProvider<BudgetRecalculationPort> budgets;
    @Autowired LoanRegistrationRepository fixtureRepository;
    @Autowired LoanInputValidator validator;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @Test void actualHttpWithoutBudgetImplementationReturns503AndStoresNothing()throws Exception {
        assertThat(budgets.getIfAvailable()).isNull();
        var browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var profile=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/users/me/")).GET().build(),HttpResponse.BodyHandlers.ofString());
        long user=mapper.readTree(profile.body()).path("data").path("user_id").longValue();
        var input=new LoanPreviewTest().body();input.put("repayment_method","BULLET");input.putNull("prepayment_policy");
        long loan=new TransactionTemplate(transactions).execute(s->fixtureRepository.insert(new PreparedLoan(validator.validate(user,input),FeeMethod.UNKNOWN,null,null,1200000,List.of()),clock.instant()));
        String body="{\"loan_id\":"+loan+",\"saved_at\":\"2026-03-01\",\"amount\":100}";
        for(int attempt=0;attempt<2;attempt++) {
            var response=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repayment-savings/"))
                .header("Origin","http://localhost:5173").header("Content-Type","application/json").header("Idempotency-Key","missing-budget")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
            assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repayment_savings WHERE user_id=?",Long.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?",Long.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,user)).isZero();
    }
}
