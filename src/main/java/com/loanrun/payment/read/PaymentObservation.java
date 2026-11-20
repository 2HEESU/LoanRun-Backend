package com.loanrun.payment.read;

import java.time.*;
import java.util.Objects;

/** Internal observation evidence, separate from raw aggregates and public API DTOs. */
public final class PaymentObservation {
    private PaymentObservation() {}
    public record Scope(long userId,Long loanId,YearMonth month) {
        public Scope {
            Objects.requireNonNull(month);
            if(userId<=0||loanId!=null&&loanId<=0||month.getYear()<1||month.getYear()>9999)
                throw new IllegalArgumentException("Invalid observation scope");
        }
    }
    public record Snapshot(Scope scope,String fingerprint,PaymentActualTotals recorded) {
        public Snapshot { Objects.requireNonNull(scope);Objects.requireNonNull(recorded);checkFingerprint(fingerprint); }
    }
    public enum Kind { RECORDING_COMPLETE, ZERO_CONFIRMED }
    public record Confirmation(Scope scope,String fingerprint,Kind kind,Instant confirmedAt) {
        public Confirmation {
            Objects.requireNonNull(scope);Objects.requireNonNull(kind);Objects.requireNonNull(confirmedAt);checkFingerprint(fingerprint);
        }
    }
    public enum Reason { RECORDING_COMPLETE, ZERO_CONFIRMED, UNCONFIRMED, STALE_CONFIRMATION,
        ZERO_CONTRADICTS_RECORDS, FUTURE_MONTH, FUTURE_CONFIRMATION }
    public record ObservedMonth(Snapshot source,boolean observed,PaymentActualTotals actuals,Reason reason) {
        public ObservedMonth {
            Objects.requireNonNull(source);Objects.requireNonNull(reason);
            boolean confirmed=reason==Reason.RECORDING_COMPLETE||reason==Reason.ZERO_CONFIRMED;
            if(observed!=confirmed||observed!=(actuals!=null)||observed&&!source.recorded().equals(actuals))throw new IllegalArgumentException("Invalid observed values");
        }
        public String dataStatus(){return observed?"RECORDED":"INSUFFICIENT";}
    }
    private static void checkFingerprint(String value) {
        if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid source fingerprint");
    }
}
