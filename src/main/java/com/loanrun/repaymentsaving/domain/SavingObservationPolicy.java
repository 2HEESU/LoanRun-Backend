package com.loanrun.repaymentsaving.domain;

import com.loanrun.repaymentsaving.read.SavingObservation.*;
import java.time.*;
import java.util.*;

public final class SavingObservationPolicy {
    private SavingObservationPolicy() {}
    public static ObservedMonth evaluate(Snapshot snapshot,Optional<Confirmation> evidence,Instant knownAt) {
        Objects.requireNonNull(snapshot);Objects.requireNonNull(evidence);Objects.requireNonNull(knownAt);
        if(snapshot.scope().month().isAfter(YearMonth.from(knownAt.atZone(ZoneId.of("Asia/Seoul")))))return unknown(snapshot,Reason.FUTURE_MONTH);
        if(evidence.isEmpty())return unknown(snapshot,Reason.UNCONFIRMED);
        var confirmation=evidence.get();
        if(!snapshot.scope().equals(confirmation.scope()))throw new IllegalArgumentException("Saving confirmation scope mismatch");
        if(confirmation.confirmedAt().isAfter(knownAt))return unknown(snapshot,Reason.FUTURE_CONFIRMATION);
        if(snapshot.scope().month().isAfter(YearMonth.from(confirmation.confirmedAt().atZone(ZoneId.of("Asia/Seoul")))))
            return unknown(snapshot,Reason.FUTURE_MONTH);
        if(!snapshot.fingerprint().equals(confirmation.fingerprint()))return unknown(snapshot,Reason.STALE_CONFIRMATION);
        if(confirmation.kind()==Kind.ZERO_CONFIRMED&&snapshot.recorded().count()!=0)return unknown(snapshot,Reason.ZERO_CONTRADICTS_RECORDS);
        return new ObservedMonth(snapshot,true,snapshot.recorded(),confirmation.kind()==Kind.ZERO_CONFIRMED?Reason.ZERO_CONFIRMED:Reason.RECORDING_COMPLETE);
    }
    private static ObservedMonth unknown(Snapshot snapshot,Reason reason){return new ObservedMonth(snapshot,false,null,reason);}
}
