package com.loanrun.payment;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.payment.domain.PaymentObservationPolicy;
import com.loanrun.payment.read.PaymentObservation.*;
import com.loanrun.payment.read.PaymentActualTotals;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaymentObservationPolicyTest {
    final Scope scope=new Scope(7L,1L,YearMonth.of(2026,3));
    final String hash="a".repeat(64);
    final Instant now=Instant.parse("2026-03-31T01:00:00Z");
    PaymentActualTotals empty(){return new PaymentActualTotals(0,0,0,0,0,0,0,0,0,0,0);}
    PaymentActualTotals positive(){return new PaymentActualTotals(1,1,0,0,115,100,0,10,5,30,85);}
    Snapshot snapshot(PaymentActualTotals totals){return new Snapshot(scope,hash,totals);}
    Confirmation evidence(Snapshot s,Kind kind){return new Confirmation(s.scope(),s.fingerprint(),kind,now.minusSeconds(1));}
    ObservedMonth apply(Snapshot s,Confirmation evidence){return PaymentObservationPolicy.evaluate(s,Optional.ofNullable(evidence),now);}

    @Test void neitherAnEmptyMonthNorPositiveRecordedPaymentsProveCompletion() {
        for(var totals:List.of(empty(),positive())) {
            var s=snapshot(totals);var result=apply(s,null);
            assertThat(result.observed()).isFalse();assertThat(result.actuals()).isNull();
            assertThat(result.source().recorded()).isEqualTo(totals);assertThat(result.dataStatus()).isEqualTo("INSUFFICIENT");
            assertThat(result.reason()).isEqualTo(Reason.UNCONFIRMED);
        }
    }
    @Test void explicitCompletionReleasesActualAmountsIncludingIndividualZeroComponents() {
        var s=snapshot(positive());var result=apply(s,evidence(s,Kind.RECORDING_COMPLETE));
        assertThat(result.observed()).isTrue();assertThat(result.actuals()).isEqualTo(positive());
        assertThat(result.actuals().extraPrincipal()).isZero();assertThat(result.dataStatus()).isEqualTo("RECORDED");
    }
    @Test void explicitZeroAndCompletedEmptyRecordingBothRetainRealZeroValues() {
        var s=snapshot(empty());
        for(var kind:Kind.values()) {
            var result=apply(s,evidence(s,kind));assertThat(result.observed()).isTrue();assertThat(result.actuals()).isNotNull();
            assertThat(result.actuals().amount()).isZero();assertThat(result.actuals().cashPaid()).isZero();
        }
    }
    @Test void cashZeroFromSavingsIsNotZeroPaymentPerformance() {
        var s=snapshot(new PaymentActualTotals(1,1,0,0,100,100,0,0,0,100,0));
        var zero=apply(s,evidence(s,Kind.ZERO_CONFIRMED));assertThat(zero.observed()).isFalse();
        assertThat(zero.actuals()).isNull();assertThat(zero.reason()).isEqualTo(Reason.ZERO_CONTRADICTS_RECORDS);
        var complete=apply(s,evidence(s,Kind.RECORDING_COMPLETE));assertThat(complete.observed()).isTrue();
        assertThat(complete.actuals().amount()).isEqualTo(100);assertThat(complete.actuals().cashPaid()).isZero();
    }
    @Test void sameAmountsWithAChangedSourceFingerprintRequireNewConfirmation() {
        var s=snapshot(positive());var old=new Confirmation(scope,"b".repeat(64),Kind.RECORDING_COMPLETE,now.minusSeconds(1));
        var result=apply(s,old);assertThat(result.observed()).isFalse();assertThat(result.actuals()).isNull();
        assertThat(result.reason()).isEqualTo(Reason.STALE_CONFIRMATION);
    }
    @Test void anotherUserLoanMonthOrHouseholdScopeCannotSupplyEvidence() {
        var s=snapshot(empty());
        for(var other:List.of(new Scope(8L,1L,scope.month()),new Scope(7L,2L,scope.month()),
            new Scope(7L,1L,scope.month().minusMonths(1)),new Scope(7L,null,scope.month()))) {
            var wrong=new Confirmation(other,hash,Kind.ZERO_CONFIRMED,now.minusSeconds(1));
            assertThatThrownBy(()->apply(s,wrong)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void futureMonthsAndFutureEvidenceNeverCountAndMonthBoundaryUsesKst() {
        var boundary=Instant.parse("2026-03-31T15:30:00Z"); // April1 KST, March31 UTC.
        var april=new Snapshot(new Scope(7L,1L,YearMonth.of(2026,4)),hash,empty());
        var aprilConfirmed=new Confirmation(april.scope(),hash,Kind.ZERO_CONFIRMED,boundary.minusSeconds(1));
        assertThat(PaymentObservationPolicy.evaluate(april,Optional.of(aprilConfirmed),boundary).observed()).isTrue();
        var may=new Snapshot(new Scope(7L,1L,YearMonth.of(2026,5)),hash,empty());
        assertThat(PaymentObservationPolicy.evaluate(may,Optional.empty(),boundary).reason()).isEqualTo(Reason.FUTURE_MONTH);
        var futureEvidence=new Confirmation(scope,hash,Kind.RECORDING_COMPLETE,now.plusSeconds(1));
        assertThat(apply(snapshot(positive()),futureEvidence).reason()).isEqualTo(Reason.FUTURE_CONFIRMATION);
    }
}
