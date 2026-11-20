package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties="server.servlet.session.cookie.secure=false")
class UserWithdrawalIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);
        r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient client,String method,String body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/users/me/"));
        if(!method.equals("GET"))request.header("Origin","http://localhost:5173");
        request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    long owner(HttpClient client)throws Exception{return json.readTree(call(client,"GET",null).body()).path("data").path("user_id").asLong();}
    long count(String table,long id){return jdbc.queryForObject("SELECT count(*) FROM "+table+(table.equals("demo_users")?" WHERE id=?":" WHERE user_id=?"),Long.class,id);}
    @Test void confirmationIsRequiredAndWithdrawalErasesOwnerButPreservesAnotherSession()throws Exception {
        var first=browser();var second=browser();long a=owner(first),b=owner(second);
        for(String body:new String[]{null,"{}","{\"confirm\":false}","{\"confirm\":\"true\"}"}) {
            var denied=call(first,"DELETE",body);
            assertThat(denied.statusCode()).as(denied.body()).isEqualTo(400);
            assertThat(json.readTree(denied.body()).path("code").asText()).isEqualTo("CONFIRMATION_REQUIRED");
            assertThat(count("demo_users",a)).isEqualTo(1);
        }
        jdbc.update("INSERT INTO notification_settings(user_id) VALUES (?) ON CONFLICT DO NOTHING",a);
        jdbc.update("INSERT INTO comparison_settings(user_id,consented) VALUES (?,false)",a);
        jdbc.update("INSERT INTO report_attempts(user_id,attempted_at) VALUES (?,CURRENT_TIMESTAMP)",a);
        jdbc.update("INSERT INTO report_jobs(user_id,scope_key,data_revision,basis_month,status,input_payload,created_at,updated_at) VALUES (?,'HOUSEHOLD',0,'2026-11-01','PENDING','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",a);
        jdbc.update("INSERT INTO monthly_budgets(user_id,year_month,income_type,expected_income,payday,fixed_essential_expenses,variable_essential_expenses,discretionary_expenses,discretionary_limit,savings,created_at,updated_at) VALUES (?,'2026-11-01','REGULAR',3000000,25,0,0,0,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",a);
        long budget=jdbc.queryForObject("SELECT id FROM monthly_budgets WHERE user_id=?",Long.class,a);
        jdbc.update("INSERT INTO monthly_allocations(budget_id,loan_id,allocation_type,amount) VALUES (?,999999,'EXTRA_PAYMENT',100)",budget);
        jdbc.update("INSERT INTO income_entries(user_id,budget_id,entry_type,received_date,amount,created_at) VALUES (?,?,'EXTRA','2026-11-20',100,CURRENT_TIMESTAMP)",a,budget);
        jdbc.update("INSERT INTO finance_change_events(user_id,data_revision,origin,basis_month,event_date,kind,before_amount,after_amount,cash_impact_known,created_at) VALUES (?,1,'PROFILE','2026-11-01','2026-11-20','INCOME',100,200,true,CURRENT_TIMESTAMP)",a);
        jdbc.update("INSERT INTO finance_change_events(user_id,data_revision,origin,basis_month,event_date,kind,before_amount,after_amount,cash_impact_known,created_at) VALUES (?,1,'PROFILE','2026-11-01','2026-11-20','INCOME',100,200,true,CURRENT_TIMESTAMP)",b);
        assertThatThrownBy(()->jdbc.update("DELETE FROM finance_change_events WHERE user_id=?",a)).isInstanceOf(Exception.class);
        var ok=call(first,"DELETE","{\"confirm\":true}");
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(json.readTree(ok.body()).path("code").asText()).isEqualTo("USER_DELETE_SUCCESS");
        assertThat(json.readTree(ok.body()).path("data").path("deleted").booleanValue()).isTrue();
        for(var table:List.of("demo_users","notification_settings","comparison_settings","report_attempts","report_jobs","monthly_budgets","income_entries","finance_change_events"))
            assertThat(count(table,a)).as(table).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM monthly_allocations WHERE budget_id=?",Long.class,budget)).isZero();
        assertThat(owner(second)).isEqualTo(b);
        assertThat(count("finance_change_events",b)).isEqualTo(1);
        long next=owner(first);
        assertThat(next).isNotEqualTo(a);
        assertThat(json.readTree(call(first,"GET",null).body()).path("data").path("onboarding_completed").booleanValue()).isFalse();
    }
}
