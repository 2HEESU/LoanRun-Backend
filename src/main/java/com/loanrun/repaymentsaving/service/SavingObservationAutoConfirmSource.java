package com.loanrun.repaymentsaving.service;

import com.loanrun.repaymentsaving.read.SavingObservation.*;
import com.loanrun.repaymentsaving.read.SavingObservationSource;
import com.loanrun.repaymentsaving.repository.SavingObservationSnapshotRepository;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Demo/MVP evidence source for BULLET savings: months with recorded additions are bound to their exact fingerprint. */
@Component
@ConditionalOnProperty(name="loanrun.observation.auto-confirm",havingValue="true")
public class SavingObservationAutoConfirmSource implements SavingObservationSource {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private final SavingObservationSnapshotRepository snapshots;
    public SavingObservationAutoConfirmSource(SavingObservationSnapshotRepository snapshots){this.snapshots=snapshots;}
    @Override public Optional<Confirmation> find(Scope scope) {
        var snapshot=snapshots.snapshot(scope);
        if(snapshot.recorded().count()==0)return Optional.empty();
        return Optional.of(new Confirmation(scope,snapshot.fingerprint(),Kind.RECORDING_COMPLETE,scope.month().atDay(1).atStartOfDay(KST).toInstant()));
    }
}
