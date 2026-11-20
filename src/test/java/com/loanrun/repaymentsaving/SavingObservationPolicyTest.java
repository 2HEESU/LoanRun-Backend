package com.loanrun.repaymentsaving;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.repaymentsaving.domain.SavingObservationPolicy;
import com.loanrun.repaymentsaving.read.SavingObservation.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SavingObservationPolicyTest {
    final Scope scope=new Scope(7,1,YearMonth.of(2026,3));
    final Instant now=Instant.parse("2026-03-31T01:00:00Z");
    Snapshot snapshot(Recorded totals){return new Snapshot(scope,"a".repeat(64),totals);}
    Confirmation proof(Snapshot s,Kind kind){return new Confirmation(s.scope(),s.fingerprint(),kind,now.minusSeconds(1));}
    ObservedMonth apply(Snapshot s,Confirmation proof){return SavingObservationPolicy.evaluate(s,Optional.ofNullable(proof),now);}
    @Test void missingEvidenceNeverTurnsEmptyOrPositiveRecordsIntoObservedPerformance() {
        for(var recorded:List.of(new Recorded(0,0),new Recorded(1,100))) {
            var result=apply(snapshot(recorded),null);assertThat(result.observed()).isFalse();assertThat(result.actuals()).isNull();
            assertThat(result.source().recorded()).isEqualTo(recorded);
        }
    }
    @Test void completionCanConfirmPositiveAdditionsOrActualZero() {
        for(var recorded:List.of(new Recorded(0,0),new Recorded(2,300))) {
            var s=snapshot(recorded);var result=apply(s,proof(s,Kind.RECORDING_COMPLETE));
            assertThat(result.observed()).isTrue();assertThat(result.actuals()).isEqualTo(recorded);
        }
    }
    @Test void explicitZeroRequiresAnEmptySavingLedger() {
        var empty=snapshot(new Recorded(0,0));assertThat(apply(empty,proof(empty,Kind.ZERO_CONFIRMED)).actuals().additions()).isZero();
        var positive=snapshot(new Recorded(1,100));var result=apply(positive,proof(positive,Kind.ZERO_CONFIRMED));
        assertThat(result.observed()).isFalse();assertThat(result.reason()).isEqualTo(Reason.ZERO_CONTRADICTS_RECORDS);
    }
    @Test void staleFingerprintRequiresNewEvidence() {
        var s=snapshot(new Recorded(1,100));var result=apply(s,new Confirmation(scope,"b".repeat(64),Kind.RECORDING_COMPLETE,now));
        assertThat(result.actuals()).isNull();assertThat(result.reason()).isEqualTo(Reason.STALE_CONFIRMATION);
    }
    @Test void ownerLoanAndMonthMustMatchExactly() {
        var s=snapshot(new Recorded(0,0));
        for(var other:List.of(new Scope(8,1,scope.month()),new Scope(7,2,scope.month()),new Scope(7,1,scope.month().minusMonths(1))))
            assertThatThrownBy(()->apply(s,new Confirmation(other,s.fingerprint(),Kind.ZERO_CONFIRMED,now))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void futureEvidenceAndMonthsAreUnknownAndCalendarBoundaryUsesKst() {
        var s=snapshot(new Recorded(0,0));assertThat(apply(s,new Confirmation(scope,s.fingerprint(),Kind.ZERO_CONFIRMED,now.plusSeconds(1))).reason())
            .isEqualTo(Reason.FUTURE_CONFIRMATION);
        var april=new Snapshot(new Scope(7,1,YearMonth.of(2026,4)),s.fingerprint(),new Recorded(0,0));
        assertThat(SavingObservationPolicy.evaluate(april,Optional.empty(),now).reason()).isEqualTo(Reason.FUTURE_MONTH);
        var boundary=Instant.parse("2026-03-31T15:00:00Z");
        assertThat(SavingObservationPolicy.evaluate(april,Optional.of(new Confirmation(april.scope(),april.fingerprint(),Kind.ZERO_CONFIRMED,boundary)),boundary).observed()).isTrue();
    }
    @Test void impossibleTotalsAndForgedObservedValuesAreRejected() {
        assertThatThrownBy(()->new Recorded(1,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Recorded(0,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Recorded(-1,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Snapshot(scope,"invalid",new Recorded(0,0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ObservedMonth(snapshot(new Recorded(0,0)),true,new Recorded(0,0),Reason.UNCONFIRMED)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void aFutureMonthsPrematureConfirmationDoesNotBecomeValidWhenThatMonthLaterArrives() {
        var s=snapshot(new Recorded(0,0));
        var proof=new Confirmation(scope,s.fingerprint(),Kind.ZERO_CONFIRMED,Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(apply(s,proof).reason()).isEqualTo(Reason.FUTURE_MONTH);assertThat(apply(s,proof).observed()).isFalse();
    }
}
