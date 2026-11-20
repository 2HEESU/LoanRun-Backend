package com.loanrun.payment.service;

import com.loanrun.payment.read.PaymentObservation.*;
import com.loanrun.payment.read.PaymentObservationSource;
import com.loanrun.payment.repository.PaymentObservationSnapshotRepository;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Demo/MVP evidence source: a month that already has at least one recorded payment is treated as recorded.
 * Months without any payment row stay unconfirmed (null), so a missing record is never inferred as actual zero.
 * The confirmation is bound to the exact source fingerprint, so later edits make it stale automatically.
 */
@Component
@ConditionalOnProperty(name="loanrun.observation.auto-confirm",havingValue="true")
public class PaymentObservationAutoConfirmSource implements PaymentObservationSource {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private final PaymentObservationSnapshotRepository snapshots;
    public PaymentObservationAutoConfirmSource(PaymentObservationSnapshotRepository snapshots){this.snapshots=snapshots;}
    @Override public Optional<Confirmation> find(Scope scope) {
        var snapshot=snapshots.snapshot(scope);
        if(snapshot.recorded().paymentCount()==0)return Optional.empty();
        // Start of the observed month: never after the caller's knownAt for current/past months.
        return Optional.of(new Confirmation(scope,snapshot.fingerprint(),Kind.RECORDING_COMPLETE,scope.month().atDay(1).atStartOfDay(KST).toInstant()));
    }
}
