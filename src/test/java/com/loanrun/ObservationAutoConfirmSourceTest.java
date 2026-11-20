package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loanrun.payment.domain.PaymentObservationPolicy;
import com.loanrun.payment.read.PaymentActualTotals;
import com.loanrun.payment.read.PaymentObservation;
import com.loanrun.payment.repository.PaymentObservationSnapshotRepository;
import com.loanrun.payment.service.PaymentObservationAutoConfirmSource;
import com.loanrun.repaymentsaving.domain.SavingObservationPolicy;
import com.loanrun.repaymentsaving.read.SavingObservation;
import com.loanrun.repaymentsaving.repository.SavingObservationSnapshotRepository;
import com.loanrun.repaymentsaving.service.SavingObservationAutoConfirmSource;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ObservationAutoConfirmSourceTest {
    static final String FINGERPRINT="a".repeat(64);
    static final Instant NOW=Instant.parse("2026-11-20T09:00:00Z");
    static final YearMonth PAST=YearMonth.of(2026,10),CURRENT=YearMonth.of(2026,11),FUTURE=YearMonth.of(2026,12);

    PaymentObservation.Snapshot paymentSnapshot(PaymentObservation.Scope scope,long count) {
        var totals=count==0?new PaymentActualTotals(0,0,0,0,0,0,0,0,0,0,0)
            :new PaymentActualTotals(count,count,0,0,count*120,count*100,0,count*20,0,0,count*120);
        return new PaymentObservation.Snapshot(scope,FINGERPRINT,totals);
    }
    PaymentObservationAutoConfirmSource payment(long count) {
        var repository=mock(PaymentObservationSnapshotRepository.class);
        when(repository.snapshot(any())).thenAnswer(i->paymentSnapshot(i.getArgument(0),count));
        return new PaymentObservationAutoConfirmSource(repository);
    }
    @Test void paymentMonthWithRecordsIsObservedButMonthWithoutRecordsIsNotInferredAsZero() {
        for(var month:java.util.List.of(PAST,CURRENT)) {
            var scope=new PaymentObservation.Scope(7,3L,month);var source=payment(2);
            var observed=PaymentObservationPolicy.evaluate(paymentSnapshot(scope,2),source.find(scope),NOW);
            assertThat(observed.observed()).as(month.toString()).isTrue();
            assertThat(observed.actuals().regularPrincipal()).isEqualTo(200);
        }
        var scope=new PaymentObservation.Scope(7,3L,PAST);
        assertThat(payment(0).find(scope)).isEmpty();
        var unknown=PaymentObservationPolicy.evaluate(paymentSnapshot(scope,0),payment(0).find(scope),NOW);
        assertThat(unknown.observed()).isFalse();assertThat(unknown.actuals()).isNull();
    }
    @Test void paymentConfirmationBecomesStaleWhenTheSourceFingerprintChangesAndFutureMonthsStayUnknown() {
        var scope=new PaymentObservation.Scope(7,3L,PAST);var confirmation=payment(1).find(scope);
        var changed=new PaymentObservation.Snapshot(scope,"b".repeat(64),paymentSnapshot(scope,1).recorded());
        assertThat(PaymentObservationPolicy.evaluate(changed,confirmation,NOW).reason()).isEqualTo(PaymentObservation.Reason.STALE_CONFIRMATION);
        var future=new PaymentObservation.Scope(7,3L,FUTURE);
        assertThat(PaymentObservationPolicy.evaluate(paymentSnapshot(future,1),payment(1).find(future),NOW).observed()).isFalse();
    }
    SavingObservationAutoConfirmSource saving(long count) {
        var repository=mock(SavingObservationSnapshotRepository.class);
        when(repository.snapshot(any())).thenAnswer(i->new SavingObservation.Snapshot(i.getArgument(0),FINGERPRINT,
            new SavingObservation.Recorded(count,count*50)));
        return new SavingObservationAutoConfirmSource(repository);
    }
    @Test void savingMonthWithAdditionsIsObservedAndEmptyMonthStaysUnconfirmed() {
        var scope=new SavingObservation.Scope(7,3,PAST);
        var snapshot=new SavingObservation.Snapshot(scope,FINGERPRINT,new SavingObservation.Recorded(2,100));
        var observed=SavingObservationPolicy.evaluate(snapshot,saving(2).find(scope),NOW);
        assertThat(observed.observed()).isTrue();assertThat(observed.actuals().additions()).isEqualTo(100);
        assertThat(saving(0).find(scope)).isEqualTo(Optional.empty());
    }
}
