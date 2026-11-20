package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
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
@Import(SavingListIntegrationTest.Config.class)
class SavingListIntegrationTest {
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
    @Autowired PlanQueryPort runtimePlans;
    @LocalServerPort int port;
    @BeforeEach void reset(){clock.value=Instant.parse("2026-02-28T15:30:00Z");}
    @Test void runtimePortDoesNotBreakTheExplicitV2RegistrationReadCompatibility()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());
        assertThat(((com.loanrun.repaymentplan.read.RegistrationPlanFallback)runtimePlans).registrationFallbackAllowed()).isTrue();
        assertThatThrownBy(()->runtimePlans.currentPlans(user,LocalDate.of(2026,3,1)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        assertThat(get(b,path(id)).statusCode()).isEqualTo(200);
    }
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

    @Test void initialAssetsAreNotManufacturedSavingRecordsOrMonthlyPerformance()throws Exception {
        var b=browser();long id=loan(b,input());var r=get(b,path(id));assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).path("code").asText()).isEqualTo("SAVING_LIST_SUCCESS");
        assertThat(json(r).path("message").asText()).isEqualTo("상환자금 적립 내역 조회 성공");
        var d=json(r).path("data");assertThat(d.path("savings_balance").longValue()).isEqualTo(2000000);
        assertThat(d.path("actual_saving_amount").longValue()).isZero();assertThat(d.path("results").size()).isZero();
        assertThat(d.path("required_monthly_saving").longValue()).isEqualTo(1000000);
        assertThat(d.path("planned_saving_amount").longValue()).isEqualTo(1000000);
        assertThat(d.path("saving_progress_pct").decimalValue()).isEqualByComparingTo("16.67");
        assertThat(d.has("observed")).isFalse();
    }
    @Test void dateFiltersAffectOnlyResultsAndKeepOriginalDateIdOrder()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());day(20);
        saving(user,id,"2026-03-02",100000);long first=saving(user,id,"2026-03-05",200000),second=saving(user,id,"2026-03-05",300000);
        saving(user,id,"2026-03-10",400000);payment(user,id,"2026-03-06",250000,10000);
        var d=list(b,id,"&from=2026-03-05&to=2026-03-05");
        assertThat(d.path("results").size()).isEqualTo(2);assertThat(d.path("results").get(0).path("saving_id").longValue()).isEqualTo(first);
        assertThat(d.path("results").get(1).path("saving_id").longValue()).isEqualTo(second);
        assertThat(d.path("savings_balance").longValue()).isEqualTo(2750000);
        assertThat(d.path("actual_saving_amount").longValue()).isEqualTo(1000000);
        assertThat(d.path("remaining_principal").longValue()).isEqualTo(12000000);
        assertThat(list(b,id,"&from=2026-03-07&to=2026-03-09").path("results").size()).isZero();
        assertThat(d.path("results").get(0).path("created_at").asText()).isEqualTo("2026-03-20T00:00:00+09:00");
    }
    @Test void snapshotBoundaryAndStatusDetermineEditabilityWithoutHidingHistory()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());day(20);
        saving(user,id,"2026-03-04",100);saving(user,id,"2026-03-05",200);saving(user,id,"2026-03-06",300);
        jdbc.update("UPDATE loans SET snapshot_date='2026-03-05' WHERE id=?",id);
        var items=list(b,id,"").path("results");assertThat(items.get(0).path("editable").booleanValue()).isFalse();
        assertThat(items.get(1).path("editable").booleanValue()).isTrue();assertThat(items.get(2).path("editable").booleanValue()).isTrue();
        for(String status:List.of("PAID","DELETED")) {
            jdbc.update("UPDATE loans SET status=? WHERE id=?",status,id);
            var d=list(b,id,"");assertThat(d.path("results").size()).isEqualTo(3);
            assertThat(d.path("results")).allSatisfy(i->assertThat(i.path("editable").booleanValue()).isFalse());
            assertThat(d.path("required_monthly_saving").longValue()).isZero();assertThat(d.path("planned_saving_amount").longValue()).isZero();
        }
    }
    @Test void foreignAndMissingIdsHaveIdenticalResourceErrors()throws Exception {
        var a=browser();var b=browser();long id=loan(a,input());
        var foreign=get(b,path(id));var missing=get(b,path(Long.MAX_VALUE));
        assertThat(foreign.statusCode()).isEqualTo(404);assertThat(json(foreign)).isEqualTo(json(missing));
        assertThat(json(foreign).path("code").asText()).isEqualTo("SAVING_NOT_FOUND");
    }
    @Test void anotherLoanWithMalformedPlanCannotBreakSelectedLoan()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input()),other=loan(b,input());
        jdbc.update("UPDATE loans SET initial_plan_snapshot='{}'::jsonb WHERE id=?",other);
        assertThat(list(b,id,"").path("loan_id").longValue()).isEqualTo(id);
        assertThat(get(b,path(other)).statusCode()).isEqualTo(503);
    }
    @Test void installmentLoanUsesSavingSpecificValidationCode()throws Exception {
        var b=browser();long id=loan(b,new LoanPreviewTest().body());var r=get(b,path(id));
        assertThat(r.statusCode()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("SAVING_VALIDATION_ERROR");
    }
    @ParameterizedTest @ValueSource(strings={"","loan_id=","loan_id=abc","loan_id=0","loan_id=-1","loan_id=9223372036854775808","loan_id=1&loan_id=2","loan_id=1&from=","loan_id=1&from=2026-02-30","loan_id=1&to=2026-2-01","loan_id=1&from=2026-03-02&to=2026-03-01","loan_id=1&user_id=2","loan_id=1&from=0000-01-01"})
    void invalidQueryUsesExactSavingError(String query)throws Exception {
        var r=get(browser(),"/api/repayment-savings/?"+query);assertThat(r.statusCode()).isEqualTo(400);
        var body=json(r);assertThat(body.path("code").asText()).isEqualTo("SAVING_VALIDATION_ERROR");
        assertThat(body.path("message").asText()).isEqualTo("대출 유형과 조회 기간을 확인해 주세요.");assertThat(body.path("errors").size()).isZero();
    }
    @Test void kstCutoffAndCurrentMonthDoNotIncludeFutureActuals()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());
        saving(user,id,"2026-03-01",100);saving(user,id,"2026-03-02",900);payment(user,id,"2026-03-02",100,0);
        var d=list(b,id,"&to=2026-03-31");assertThat(d.path("results").size()).isEqualTo(1);
        assertThat(d.path("savings_balance").longValue()).isEqualTo(2000100);assertThat(d.path("actual_saving_amount").longValue()).isEqualTo(100);
        day(2);d=list(b,id,"");assertThat(d.path("results").size()).isEqualTo(2);assertThat(d.path("savings_balance").longValue()).isEqualTo(2000900);
    }
    @Test void zeroPrincipalHasNullProgressAndAmountsAreNotClampedToPrincipal()throws Exception {
        var b=browser();long id=loan(b,input());jdbc.update("UPDATE loans SET remaining_principal=0,status='PAID' WHERE id=?",id);
        var d=list(b,id,"");assertThat(d.path("saving_progress_pct").isNull()).isTrue();assertThat(d.path("savings_balance").longValue()).isEqualTo(2000000);
    }
    @Test void requiredSavingRoundsUpAndKeepsThisSavingCycleAfterInterestWasPaidEarly()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());long payment=payment(user,id,"2026-03-01",0,60000);
        jdbc.update("INSERT INTO payment_allocations(user_id,loan_id,payment_id,due_item_id,principal_amount,interest_amount,fee_amount) SELECT ?,?,?,id,0,60000,0 FROM due_items WHERE loan_id=?",user,id,payment,id);
        saving(user,id,"2026-03-01",1);var d=list(b,id,"");assertThat(d.path("required_monthly_saving").longValue()).isEqualTo(1000000);
        var loanList=json(get(b,"/api/loans/")).path("data").path("loans").get(0);
        assertThat(loanList.path("required_monthly_saving").longValue()).isEqualTo(d.path("required_monthly_saving").longValue());
    }
    @Test void afterMaturityRemainingShortageIsImmediateNotDividedByZero()throws Exception {
        var b=browser();long id=loan(b,input());clock.value=Instant.parse("2027-01-01T00:00:00Z");
        var d=list(b,id,"");assertThat(d.path("required_monthly_saving").longValue()).isEqualTo(10000000);
    }
    @Test void knownNegativeEarlierDayCannotBeHiddenByLaterSaving()throws Exception {
        var b=browser();long user=owner(b);var input=input();input.put("initial_savings_balance",0);long id=loan(b,input);day(20);
        payment(user,id,"2026-03-02",100,0);saving(user,id,"2026-03-03",200);
        assertThat(get(b,path(id)).statusCode()).isEqualTo(503);
    }
    @Test void amountOverflowFailsWithoutWrappedBalance()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());saving(user,id,"2026-03-01",Long.MAX_VALUE);
        assertThat(get(b,path(id)).statusCode()).isEqualTo(503);
    }
    @Test void repeatedGetKeepsFinancialRecordsRevisionAndTimestampUnchanged()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());saving(user,id,"2026-03-01",100);
        var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));var account=jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",user);
        assertThat(list(b,id,"")).isEqualTo(list(b,id,""));assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",user)).isEqualTo(account);
        assertThat(get(b,"/api/repayment-savings?loan_id="+id).statusCode()).isEqualTo(404);
    }

    SavingListService direct(PlanQueryPort plan,BudgetQueryPort budget) {
        var factory=new StaticListableBeanFactory();if(plan!=null)factory.addBean("plans",plan);if(budget!=null)factory.addBean("budgets",budget);
        return new SavingListService(sources,factory.getBeanProvider(PlanQueryPort.class),factory.getBeanProvider(BudgetQueryPort.class),clock);
    }
    LinkedMultiValueMap<String,String> query(long id){var q=new LinkedMultiValueMap<String,String>();q.add("loan_id",Long.toString(id));return q;}
    PlanSnapshot current(long id){return new PlanSnapshot(id,true,LocalDate.of(2026,6,25),null,0,2600000,2500000,"TARGET_DATE",null,LocalDate.of(2026,3,25),2);}
    @Test void realCurrentPlanAndExplicitZeroOverrideTakePrecedenceOverRegistrationDefaults()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());var plans=mock(PlanQueryPort.class);var budget=mock(BudgetQueryPort.class);
        when(plans.currentPlans(user,LocalDate.of(2026,3,1))).thenReturn(List.of(current(id)));
        when(budget.monthlyOverrides(user,YearMonth.of(2026,3))).thenReturn(List.of());
        var service=direct(plans,budget);var q=query(id);var result=service.list(user,q);
        assertThat(result.plannedSavingAmount()).isEqualTo(2600000);assertThat(result.requiredMonthlySaving()).isEqualTo(2500000);
        when(budget.monthlyOverrides(user,YearMonth.of(2026,3))).thenReturn(List.of(new PlannedAmount(id,AllocationType.REPAYMENT_SAVING,0,null)));
        assertThat(service.list(user,q).plannedSavingAmount()).isZero();
        assertThat(sources.loan(user,id).initialPlan().monthlySaving()).isEqualTo(1000000);
    }
    @Test void installedButMissingCurrentPlanDoesNotFallBackToStaleInitialPlan()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());var plans=mock(PlanQueryPort.class);
        when(plans.currentPlans(user,LocalDate.of(2026,3,1))).thenReturn(List.of());
        assertThatThrownBy(()->direct(plans,null).list(user,query(id))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        when(plans.currentPlans(user,LocalDate.of(2026,3,1))).thenReturn(List.of(current(id),current(id)));
        assertThatThrownBy(()->direct(plans,null).list(user,query(id))).isInstanceOf(ApiException.class);
    }
    @Test void brokenOverrideSourceDoesNotReturnInventedDefaults()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());var budget=mock(BudgetQueryPort.class);var service=direct(null,budget);
        when(budget.monthlyOverrides(user,YearMonth.of(2026,3))).thenReturn(null);
        assertThatThrownBy(()->service.list(user,query(id))).isInstanceOf(ApiException.class);
        when(budget.monthlyOverrides(user,YearMonth.of(2026,3))).thenReturn(List.of(new PlannedAmount(id,AllocationType.EXTRA_PAYMENT,1,null)));
        assertThatThrownBy(()->service.list(user,query(id))).isInstanceOf(ApiException.class);
        when(budget.monthlyOverrides(user,YearMonth.of(2026,3))).thenReturn(List.of(new PlannedAmount(id,AllocationType.REPAYMENT_SAVING,1,null),new PlannedAmount(id,AllocationType.REPAYMENT_SAVING,2,null)));
        assertThatThrownBy(()->service.list(user,query(id))).isInstanceOf(ApiException.class);
    }
    @Test void inactiveHistoryDoesNotDependOnCurrentActivePlanOrBudgetProviders()throws Exception {
        var b=browser();long user=owner(b),id=loan(b,input());jdbc.update("UPDATE loans SET status='DELETED' WHERE id=?",id);
        var plans=mock(PlanQueryPort.class);var budget=mock(BudgetQueryPort.class);
        assertThat(direct(plans,budget).list(user,query(id)).savingsBalance()).isEqualTo(2000000);
        verifyNoInteractions(plans,budget);
    }
    @Test void currentMonthRawRecordSumIsIndependentOfHistoricalResults()throws Exception {
        clock.value=Instant.parse("2026-01-31T15:30:00Z");var b=browser();long user=owner(b),id=loan(b,input());
        saving(user,id,"2026-02-28",1000);saving(user,id,"2026-03-01",500);day(1);
        var d=list(b,id,"&from=2026-02-01&to=2026-02-28");assertThat(d.path("results").size()).isEqualTo(1);
        assertThat(d.path("actual_saving_amount").longValue()).isEqualTo(500);assertThat(d.path("savings_balance").longValue()).isEqualTo(2001500);
    }
    @Test void sameDateSavingAndUseRetainBothOriginalRecords()throws Exception {
        var b=browser();long user=owner(b);var input=input();input.put("initial_savings_balance",0);long id=loan(b,input);
        saving(user,id,"2026-03-01",200);payment(user,id,"2026-03-01",100,0);
        var d=list(b,id,"");assertThat(d.path("results").size()).isEqualTo(1);
        assertThat(d.path("actual_saving_amount").longValue()).isEqualTo(200);assertThat(d.path("savings_balance").longValue()).isEqualTo(100);
    }
}
