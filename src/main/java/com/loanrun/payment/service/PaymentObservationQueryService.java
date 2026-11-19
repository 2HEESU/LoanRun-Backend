package com.loanrun.payment.service;

import com.loanrun.common.error.ApiException;
import com.loanrun.payment.domain.PaymentObservationPolicy;
import com.loanrun.payment.read.PaymentObservation.*;
import com.loanrun.payment.read.PaymentObservationSource;
import com.loanrun.payment.repository.PaymentObservationSnapshotRepository;
import java.time.*;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PaymentObservationQueryService {
    private final PaymentObservationSnapshotRepository snapshots;
    private final ObjectProvider<PaymentObservationSource> sources;
    private final Clock clock;
    public PaymentObservationQueryService(PaymentObservationSnapshotRepository snapshots,ObjectProvider<PaymentObservationSource> sources,Clock clock) {
        this.snapshots=snapshots;this.sources=sources;this.clock=clock;
    }
    public Snapshot snapshot(long userId,YearMonth month,Long loanId){return snapshots.snapshot(new Scope(userId,loanId,month));}
    public ObservedMonth observe(long userId,YearMonth month,Long loanId) {
        var now=clock.instant();var snapshot=snapshot(userId,month,loanId);
        var source=sources.getIfAvailable();
        try {
            Optional<Confirmation> evidence=source==null?Optional.empty():source.find(snapshot.scope());
            if(evidence==null)throw ApiException.unavailable();
            return PaymentObservationPolicy.evaluate(snapshot,evidence,now);
        }
        catch(IllegalArgumentException ex){throw ApiException.unavailable();}
    }
}
