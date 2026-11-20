package com.loanrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties="server.servlet.session.cookie.secure=false")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OnboardingRuntimeIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);
        r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test void actualProvidersAllowNewSessionToStartAndRejectPrematureCompletion() throws Exception {
        var browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var progress=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/onboarding/"))
            .GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(progress.statusCode()).as(progress.body()).isEqualTo(200);
        var data=mapper.readTree(progress.body()).path("data");
        assertThat(data.path("next_step").asText()).isEqualTo("LOAN");
        assertThat(data.path("registered_loan_ids").isEmpty()).isTrue();
        assertThat(data.path("finance_profile_exists").asBoolean()).isFalse();
        assertThat(data.path("completed").asBoolean()).isFalse();
        var completion=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/onboarding/complete/"))
            .header("Origin","http://localhost:5173").POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(completion.statusCode()).as(completion.body()).isEqualTo(400);
        assertThat(mapper.readTree(completion.body()).path("code").asText()).isEqualTo("ONBOARDING_INCOMPLETE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_users WHERE onboarding_completed=TRUE",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT sum(revision) FROM demo_users",Long.class)).isZero();
    }
}
