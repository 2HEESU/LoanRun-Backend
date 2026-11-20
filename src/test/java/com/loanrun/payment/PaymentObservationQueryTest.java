package com.loanrun.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.payment.read.PaymentObservation.*;
import com.loanrun.payment.read.*;
import com.loanrun.payment.repository.PaymentObservationSnapshotRepository;
import com.loanrun.payment.service.PaymentObservationQueryService;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class PaymentObservationQueryTest {
    final Scope scope=new Scope(7L,1L,YearMonth.of(2026,3));
    final Snapshot snapshot=new Snapshot(scope,"a".repeat(64),new PaymentActualTotals(0,0,0,0,0,0,0,0,0,0,0));
    final Clock clock=Clock.fixed(Instant.parse("2026-03-31T01:00:00Z"),ZoneOffset.UTC);
    PaymentObservationQueryService service(StaticListableBeanFactory beans) {
        var repo=mock(PaymentObservationSnapshotRepository.class);when(repo.snapshot(scope)).thenReturn(snapshot);
        return new PaymentObservationQueryService(repo,beans.getBeanProvider(PaymentObservationSource.class),clock);
    }
    @Test void actualMissingSourceDoesNotInstallADefaultConfirmation() {
        var result=service(new StaticListableBeanFactory()).observe(7,scope.month(),1L);
        assertThat(result.observed()).isFalse();assertThat(result.actuals()).isNull();
    }
    @Test void explicitSourceEvidenceIsConsultedForTheExactOwnerMonthAndSelection() {
        var beans=new StaticListableBeanFactory();
        beans.addBean("confirmation",(PaymentObservationSource)requested->{
            assertThat(requested).isEqualTo(scope);
            return Optional.of(new Confirmation(scope,snapshot.fingerprint(),Kind.ZERO_CONFIRMED,clock.instant().minusSeconds(1)));
        });
        var result=service(beans).observe(7,scope.month(),1L);assertThat(result.observed()).isTrue();assertThat(result.actuals().amount()).isZero();
    }
    @Test void brokenOrWrongScopeSourcesFailInsteadOfGrantingAnotherUsersConfirmation() {
        for(boolean wrongScope:new boolean[]{true,false}) {
            var beans=new StaticListableBeanFactory();
            beans.addBean("badSource",(PaymentObservationSource)requested->wrongScope?Optional.of(new Confirmation(
                new Scope(8L,1L,scope.month()),snapshot.fingerprint(),Kind.ZERO_CONFIRMED,clock.instant().minusSeconds(1))):null);
            assertThatThrownBy(()->service(beans).observe(7,scope.month(),1L)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        }
    }
    @Test void sharedCaptureInstantRejectsAConfirmationThatArrivedAfterTheCompositionStarted() {
        var beans=new StaticListableBeanFactory();
        beans.addBean("confirmation",(PaymentObservationSource)requested->Optional.of(new Confirmation(scope,snapshot.fingerprint(),Kind.ZERO_CONFIRMED,clock.instant())));
        var result=service(beans).observeAt(7,scope.month(),1L,clock.instant().minusSeconds(1));
        assertThat(result.observed()).isFalse();assertThat(result.reason()).isEqualTo(Reason.FUTURE_CONFIRMATION);
    }
    @Test void aFutureCaptureInstantCannotGrantAccessToFutureEvidence() {
        assertThatThrownBy(()->service(new StaticListableBeanFactory()).observeAt(7,scope.month(),1L,clock.instant().plusSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
