package com.loanrun.payment.domain;

import com.loanrun.payment.read.PaymentObservation.*;
import java.time.*;
import java.util.Objects;
import java.util.Optional;

/** Explicit monthly evidence is necessary; any positive entry or missed due alone is insufficient. */
public final class PaymentObservationPolicy {
    private PaymentObservationPolicy() {}
    public static ObservedMonth evaluate(Snapshot snapshot,Optional<Confirmation> evidence,Instant now) {
        Objects.requireNonNull(snapshot);Objects.requireNonNull(evidence);Objects.requireNonNull(now);
        var month=YearMonth.from(now.atZone(ZoneId.of("Asia/Seoul")));
        if(snapshot.scope().month().isAfter(month))return unknown(snapshot,Reason.FUTURE_MONTH);
        if(evidence.isEmpty())return unknown(snapshot,Reason.UNCONFIRMED);
        var confirmation=evidence.get();
        if(!snapshot.scope().equals(confirmation.scope()))throw new IllegalArgumentException("Confirmation scope mismatch");
        if(confirmation.confirmedAt().isAfter(now))return unknown(snapshot,Reason.FUTURE_CONFIRMATION);
        if(!snapshot.fingerprint().equals(confirmation.fingerprint()))return unknown(snapshot,Reason.STALE_CONFIRMATION);
        if(confirmation.kind()==Kind.ZERO_CONFIRMED&&snapshot.recorded().paymentCount()!=0)
            return unknown(snapshot,Reason.ZERO_CONTRADICTS_RECORDS);
        return new ObservedMonth(snapshot,true,snapshot.recorded(),confirmation.kind()==Kind.ZERO_CONFIRMED?Reason.ZERO_CONFIRMED:Reason.RECORDING_COMPLETE);
    }
    private static ObservedMonth unknown(Snapshot snapshot,Reason reason){return new ObservedMonth(snapshot,false,null,reason);}
}
