package com.loanrun.repaymentsaving.service;

import com.loanrun.common.error.ApiException;
import com.loanrun.repaymentsaving.domain.SavingObservationPolicy;
import com.loanrun.repaymentsaving.read.SavingObservation.*;
import com.loanrun.repaymentsaving.read.SavingObservationSource;
import com.loanrun.repaymentsaving.repository.SavingObservationSnapshotRepository;
import java.time.*;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class SavingObservationQueryService {
    private final SavingObservationSnapshotRepository snapshots;
    private final ObjectProvider<SavingObservationSource> sources;
    private final Clock clock;
    public SavingObservationQueryService(SavingObservationSnapshotRepository snapshots,ObjectProvider<SavingObservationSource> sources,Clock clock) {
        this.snapshots=snapshots;this.sources=sources;this.clock=clock;
    }
    public Snapshot snapshot(long userId,long loanId,YearMonth month){return snapshots.snapshot(new Scope(userId,loanId,month));}
    public ObservedMonth observe(long userId,long loanId,YearMonth month){return observeAt(userId,loanId,month,clock.instant());}
    public ObservedMonth observeAt(long userId,long loanId,YearMonth month,Instant knownAt) {
        if(knownAt==null||knownAt.isAfter(clock.instant()))throw new IllegalArgumentException("Invalid observation time");
        var snapshot=snapshot(userId,loanId,month);var source=sources.getIfAvailable();
        try {
            Optional<Confirmation> evidence=source==null?Optional.empty():source.find(snapshot.scope());
            if(evidence==null)throw ApiException.unavailable();
            return SavingObservationPolicy.evaluate(snapshot,evidence,knownAt);
        } catch(IllegalArgumentException e){throw ApiException.unavailable();}
    }
}
