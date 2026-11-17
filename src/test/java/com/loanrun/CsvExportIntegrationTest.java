package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.LoanQueryPort;
import com.loanrun.common.contract.FinanceModels.*;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"loanrun.demo-mode=true", "server.servlet.session.cookie.secure=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CsvExportIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @MockitoBean LoanQueryPort loans;

    private HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    private HttpResponse<String> request(HttpClient client, String method, String path, String body, String origin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (origin != null) builder.header("Origin", origin);
        builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
    private long userId(HttpClient browser) throws Exception {
        return json(request(browser, "GET", "/api/users/me/", null, null)).path("data").path("user_id").longValue();
    }

    @Test
    void exportUsesOwnedProjectionsAndProtectsCsvCellsWhilePreservingFeesAndSavingsUse() throws Exception {
        var client = browser();
        long id = userId(client);
        when(loans.exportLoans(id)).thenReturn(List.of(new ExportLoan(101, " =SUM(1,2)", 12000000)));
        when(loans.exportPayments(eq(id), any())).thenReturn(List.of(new ExportPayment(101, "가상 \"대출\"", LocalDate.of(2026,11,16), 10000, 7000, 1000, 1000, 1000, 500)));
        var response = request(client, "GET", "/api/settings/export/?start_month=2026-11&end_month=2026-11", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("text/csv");
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).contains("ronrun-export.csv");
        assertThat(response.body()).startsWith("\uFEFFrecord_type,").contains("\"' =SUM(1,2)\"", "fee_amount,savings_used_amount", "\"가상 \"\"대출\"\"\"");
        assertThat(response.body()).contains(",\"1000\",\"500\"\r\n");
        verify(loans).exportPayments(id, new DateRange(LocalDate.of(2026,11,1), LocalDate.of(2026,11,30)));
        assertThat(request(client, "GET", "/api/settings/export/?format=xlsx", null, null).statusCode()).isEqualTo(400);
        assertThat(json(request(client, "GET", "/api/settings/export/?start_month=2026-12&end_month=2026-11", null, null)).path("code").asText()).isEqualTo("INVALID_DATE_RANGE");
        assertThat(request(client, "GET", "/api/settings/export/?start_month=2026-1", null, null).statusCode()).isEqualTo(400);
        var otherBrowser = browser();
        long otherId = userId(otherBrowser);
        assertThat(otherId).isNotEqualTo(id);
        var other = request(otherBrowser, "GET", "/api/settings/export/", null, null);
        assertThat(other.body()).doesNotContain("SUM", "가상");
        verify(loans).exportLoans(otherId);
    }
}
