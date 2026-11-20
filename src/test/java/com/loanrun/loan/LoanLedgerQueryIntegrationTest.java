package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.service.LoanRegistrationService;
import com.loanrun.payment.read.PaymentObservationSource;
import com.loanrun.payment.service.PaymentObservationQueryService;
import com.loanrun.repaymentsaving.read.SavingObservationSource;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest
@Import(LoanLedgerQueryIntegrationTest.Config.class)
class LoanLedgerQueryIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-02-28T15:30:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}public Clock withZone(ZoneId z){return Clock.fixed(value,z);}public Instant instant(){return value;}
    }
    @TestConfiguration static class Config {@Bean @Primary TestClock queryClock(){return new TestClock();}}
    @MockitoBean BudgetRecalculationPort budget;
    @MockitoBean PaymentObservationSource paymentProof;
    @MockitoBean SavingObservationSource savingProof;
    @Autowired LoanQueryPort port;
    @Autowired LoanRegistrationService registration;
    @Autowired PaymentObservationQueryService observations;
    @Autowired TestClock clock;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void reset(){clock.value=Instant.parse("2026-02-28T15:30:00Z");}
    LocalDate day(int d){return LocalDate.of(2026,3,d);}YearMonth month(){return YearMonth.of(2026,3);}
    long user(){var now=Timestamp.from(clock.instant());return jdbc.queryForObject("INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,?,?) RETURNING id",Long.class,UUID.randomUUID().toString(),now,now);}
    long loan(long user,boolean bullet)throws Exception {
        var body=new LoanPreviewTest().body();if(bullet){body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);}
        return registration.create(user,body,null).body().path("data").path("loan_id").longValue();
    }
    long pay(long user,long loan,LocalDate date,String kind,long principal,long extra,long interest,long fee,long used) {
        return jdbc.queryForObject("INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id",Long.class,
            user,loan,date,kind,principal+extra+interest+fee,principal,extra,interest,fee,used,Timestamp.from(clock.instant()));
    }
    void allocation(long user,long loan,long payment,long due,long principal){jdbc.update("INSERT INTO payment_allocations(user_id,loan_id,payment_id,due_item_id,principal_amount,interest_amount,fee_amount) VALUES (?,?,?,?,?,0,0)",user,loan,payment,due,principal);}
    void unavailable(Runnable call){assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));}
    void completePayments() {
        when(paymentProof.find(any())).thenAnswer(inv->{var scope=inv.<com.loanrun.payment.read.PaymentObservation.Scope>getArgument(0);var snapshot=observations.snapshot(scope.userId(),scope.month(),scope.loanId());
            return Optional.of(new com.loanrun.payment.read.PaymentObservation.Confirmation(scope,snapshot.fingerprint(),snapshot.recorded().paymentCount()==0?com.loanrun.payment.read.PaymentObservation.Kind.ZERO_CONFIRMED:com.loanrun.payment.read.PaymentObservation.Kind.RECORDING_COMPLETE,clock.instant()));});
    }
    @Test void exposesRealBeanOwnedTermsAndCurrentMaterializedBalanceWithoutDoubleSubtracting()throws Exception {
        long u=user(),id=loan(u,false);var initial=port.terms(u,id,day(1));
        assertThat(initial.outstanding()).isEqualTo(12000000);assertThat(initial.annualRate()).isEqualByComparingTo("6");
        assertThat(port.activeLoans(u,day(1))).extracting(LoanTerms::loanId).containsExactly(id);
        jdbc.update("UPDATE loans SET status='PAID',remaining_principal=0 WHERE id=?",id);
        assertThat(port.activeLoans(u,day(1))).isEmpty();assertThat(port.terms(u,id,day(1)).outstanding()).isZero();
        assertThatThrownBy(()->port.terms(user(),id,day(1))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("LOAN_NOT_FOUND"));
    }
    @Test void changedHistoricalTermsAndUnsettledFrozenTermsStayUnavailable()throws Exception {
        long u=user(),id=loan(u,false);clock.value=Instant.parse("2026-03-31T15:00:00Z");
        unavailable(()->port.terms(u,id,LocalDate.of(2026,4,1)));
        jdbc.update("UPDATE loans SET updated_at='2026-04-01T00:00:00Z' WHERE id=?",id);
        unavailable(()->port.terms(u,id,day(5)));unavailable(()->port.activeLoans(u,day(5)));
        unavailable(()->port.terms(u,id,LocalDate.of(2026,4,2)));
    }
    @Test void paidPriorArrearsAreNotChargedAgainAndCurrentPartialDueIsPreserved()throws Exception {
        long u=user(),id=loan(u,false);long due=jdbc.queryForObject("SELECT id FROM due_items WHERE loan_id=?",Long.class,id);
        jdbc.update("UPDATE due_items SET required_amount=200,principal_amount=200,interest_amount=0 WHERE id=?",due);
        long past=jdbc.queryForObject("INSERT INTO due_items(user_id,loan_id,due_date,principal_amount,interest_amount,required_amount,terms_revision,created_at) VALUES (?,?,'2026-02-25',100,0,100,1,CURRENT_TIMESTAMP) RETURNING id",Long.class,u,id);
        allocation(u,id,pay(u,id,day(1),"REGULAR",100,0,0,0,0),past,100);
        allocation(u,id,pay(u,id,day(1),"REGULAR",50,0,0,0,0),due,50);
        var summary=port.dueSummary(u,month());assertThat(summary.scheduledCash()).isEqualTo(200);assertThat(summary.previousOutstandingCash()).isZero();
        assertThat(summary.paidRequired()).isEqualTo(50);assertThat(summary.outstandingRequired()).isEqualTo(150);assertThat(summary.partialCount()).isEqualTo(1);
        assertThat(summary.nextDueDate()).isEqualTo(day(25));assertThat(summary.totalCount()).isEqualTo(1);
        unavailable(()->port.dueSummary(u,month().plusMonths(1)));
    }
    @Test void separatesCashFromAssetsAndIncludesFeesOnlyInProvenExtraCash()throws Exception {
        long u=user(),id=loan(u,true);pay(u,id,day(1),"EXTRA",0,1000,0,10,500);
        var summary=port.paymentSummary(u,month());assertThat(summary.cashPaid()).isEqualTo(510);assertThat(summary.savingsUsed()).isEqualTo(500);
        assertThat(summary.extraCashByLoan()).containsEntry(id,510L);assertThat(summary.extraCount()).isEqualTo(1);assertThat(summary.observed()).isFalse();
        completePayments();assertThat(port.paymentSummary(u,month()).observed()).isTrue();
        pay(u,id,day(1),"REGULAR",100,100,0,0,50);unavailable(()->port.paymentSummary(u,month()));
    }
    @Test void savingAssetsInitialBalanceAndActualAdditionsAreSeparateAndUnconfirmed()throws Exception {
        long u=user(),id=loan(u,true);jdbc.update("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,?,700,?)",u,id,day(1),Timestamp.from(clock.instant()));
        pay(u,id,day(1),"EXTRA",0,500,0,0,500);
        var summary=port.savingSummary(u,month());assertThat(summary.initialBalance()).isEqualTo(2000000);assertThat(summary.additions()).isEqualTo(700);
        assertThat(summary.withdrawals()).isEqualTo(500);assertThat(summary.closingBalance()).isEqualTo(2000200);assertThat(summary.observed()).isFalse();
        assertThat(summary.additionsByLoan()).containsEntry(id,700L);assertThat(summary.requiredAmount()).isPositive();
        unavailable(()->port.savingSummary(u,month().plusMonths(1)));
    }
    @Test void exportsKeepDeletedActualsAndFilterOwnerAndInclusivePaymentDatesWithoutPlanParsing()throws Exception {
        long u=user(),id=loan(u,false),other=user();loan(other,false);
        pay(u,id,day(1),"EXTRA",0,100,0,3,0);pay(u,id,day(2),"REGULAR",100,0,5,0,0);
        jdbc.update("UPDATE loans SET status='DELETED',initial_plan_snapshot='{}'::jsonb WHERE id=?",id);
        assertThat(port.exportLoans(u)).extracting(ExportLoan::loanId).containsExactly(id);
        var rows=port.exportPayments(u,new DateRange(day(1),day(1)));assertThat(rows).hasSize(1);assertThat(rows.getFirst().feeAmount()).isEqualTo(3);
        assertThat(port.exportPayments(other,new DateRange(day(1),day(2)))).isEmpty();
        assertThatThrownBy(()->rows.clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void historyRequiresExplicitCompletionAndDoesNotCreateZeroMonthsOrCurrentValueDenominators()throws Exception {
        long u=user(),id=loan(u,false);pay(u,id,day(1),"EXTRA",0,100,0,0,0);
        var range=new DateRange(day(1),day(31));assertThat(port.history(u,range)).isEmpty();
        completePayments();var march=port.history(u,range).getFirst();assertThat(march.observed()).isTrue();assertThat(march.openingBalance()).isZero();
        assertThat(march.registeredBalance()).isEqualTo(12000000);assertThat(march.extraPrincipal()).isEqualTo(100);
        clock.value=Instant.parse("2026-03-31T15:00:00Z");jdbc.update("UPDATE loans SET remaining_principal=1,status='DELETED',initial_plan_snapshot='{}'::jsonb WHERE id=?",id);
        var april=port.history(u,new DateRange(LocalDate.of(2026,4,1),LocalDate.of(2026,4,30))).getFirst();
        assertThat(april.openingBalance()).isEqualTo(11999900);assertThat(april.registeredBalance()).isZero();assertThat(april.extraPrincipal()).isZero();
        assertThat(port.history(u,new DateRange(day(2),day(31)))).isEmpty();
    }
    @Test void confirmedHistoryWithoutOriginalBalanceOrWithUnrepresentableCorrectionFails()throws Exception {
        long u=user(),id=loan(u,false);completePayments();
        jdbc.update("INSERT INTO loan_balance_snapshots(user_id,loan_id,effective_date,amount,reason,created_at) VALUES (?,?,?,100,'CORRECTION',?)",u,id,day(2),Timestamp.from(clock.instant()));
        unavailable(()->port.history(u,new DateRange(day(1),day(31))));
        jdbc.update("DELETE FROM loan_balance_snapshots WHERE loan_id=?",id);
        unavailable(()->port.history(u,new DateRange(day(1),day(31))));
    }
    @Test void paymentZeroNeedsProofAndAllQueriesLeaveRevisionAndSourcesUnchanged()throws Exception {
        long u=user();loan(u,false);var before=jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",u);
        var source=jdbc.queryForList("SELECT * FROM loans WHERE user_id=?",u);
        assertThat(port.paymentSummary(u,month()).observed()).isFalse();completePayments();assertThat(port.paymentSummary(u,month()).observed()).isTrue();
        port.terms(u,source.getFirst().get("id") instanceof Number n?n.longValue():0,day(1));port.dueSummary(u,month());port.savingSummary(u,month());
        port.history(u,new DateRange(day(1),day(31)));port.exportLoans(u);port.exportPayments(u,new DateRange(day(1),day(31)));
        assertThat(jdbc.queryForMap("SELECT * FROM demo_users WHERE id=?",u)).isEqualTo(before);assertThat(jdbc.queryForList("SELECT * FROM loans WHERE user_id=?",u)).isEqualTo(source);
    }
    @Test void materializedPrincipalIsNotReducedByTheSameActualPaymentAgain()throws Exception {
        long u=user(),id=loan(u,true);pay(u,id,day(1),"EXTRA",0,1000000,0,0,0);
        jdbc.update("UPDATE loans SET remaining_principal=11000000 WHERE id=?",id);
        jdbc.update("UPDATE due_items SET interest_amount=55000,required_amount=55000 WHERE loan_id=?",id);
        assertThat(port.terms(u,id,day(1)).outstanding()).isEqualTo(11000000);
    }
    @Test void unchangedPastTermsAreAvailableButHistoricalMembershipDoesNotUseFutureLoans()throws Exception {
        long u=user(),id=loan(u,false);clock.value=Instant.parse("2026-03-01T15:00:00Z");
        assertThat(port.activeLoans(u,day(1))).extracting(LoanTerms::loanId).containsExactly(id);
        assertThat(port.historicalLoans(u,new DateRange(day(1),day(1)))).extracting(LoanTerms::loanId).containsExactly(id);
        assertThat(port.historicalLoans(u,new DateRange(LocalDate.of(2026,2,1),LocalDate.of(2026,2,28)))).isEmpty();
    }
    @Test void deletedLoansDoNotContributeCurrentBudgetBurdenOrRequireParsingTheirOldPlan()throws Exception {
        long u=user(),id=loan(u,false);jdbc.update("UPDATE loans SET status='DELETED',initial_plan_snapshot='{}'::jsonb WHERE id=?",id);
        var due=port.dueSummary(u,month());assertThat(due.scheduledCash()).isZero();assertThat(due.previousOutstandingCash()).isZero();assertThat(due.totalCount()).isZero();
    }
    @Test void regularAssetUseIsAttributedToTheActualDueAndAmbiguousCrossMonthFundingIsRejected()throws Exception {
        long u=user(),id=loan(u,true);long due=jdbc.queryForObject("SELECT id FROM due_items WHERE loan_id=?",Long.class,id);
        long p=pay(u,id,day(1),"REGULAR",1000,0,0,0,500);allocation(u,id,p,due,1000);
        assertThat(port.dueSummary(u,month()).savingsUsedForDue()).isEqualTo(500);
        long old=jdbc.queryForObject("INSERT INTO due_items(user_id,loan_id,due_date,principal_amount,interest_amount,required_amount,terms_revision,created_at) VALUES (?,?,'2026-02-25',100,0,100,1,CURRENT_TIMESTAMP) RETURNING id",Long.class,u,id);
        allocation(u,id,p,old,100);unavailable(()->port.dueSummary(u,month()));
    }
    @Test void aLaterPaymentInvalidatesRecordingCompletionAndFutureActualsAreNotAccepted()throws Exception {
        long u=user(),id=loan(u,false);var snapshot=observations.snapshot(u,month(),null);
        when(paymentProof.find(any())).thenReturn(Optional.of(new com.loanrun.payment.read.PaymentObservation.Confirmation(snapshot.scope(),snapshot.fingerprint(),com.loanrun.payment.read.PaymentObservation.Kind.ZERO_CONFIRMED,clock.instant())));
        assertThat(port.paymentSummary(u,month()).observed()).isTrue();pay(u,id,day(1),"EXTRA",0,100,0,0,0);
        assertThat(port.paymentSummary(u,month()).observed()).isFalse();assertThat(port.history(u,new DateRange(day(1),day(31)))).isEmpty();
        pay(u,id,day(2),"EXTRA",0,100,0,0,0);unavailable(()->port.paymentSummary(u,month()));unavailable(()->port.dueSummary(u,month()));
    }
    @Test void nextActualDueCanBeInTheFollowingMonthAfterThisMonthsObligationIsSatisfied()throws Exception {
        long u=user(),id=loan(u,false);jdbc.update("UPDATE due_items SET required_amount=0,principal_amount=0,interest_amount=0 WHERE loan_id=?",id);
        jdbc.update("INSERT INTO due_items(user_id,loan_id,due_date,principal_amount,interest_amount,required_amount,terms_revision,created_at) VALUES (?,?,'2026-04-25',100,0,100,1,CURRENT_TIMESTAMP)",u,id);
        var result=port.dueSummary(u,month());assertThat(result.outstandingRequired()).isZero();assertThat(result.paidCount()).isEqualTo(1);
        assertThat(result.nextDueDate()).isEqualTo(LocalDate.of(2026,4,25));
    }
}
