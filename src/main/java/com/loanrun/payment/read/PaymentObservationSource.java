package com.loanrun.payment.read;

import com.loanrun.payment.read.PaymentObservation.*;
import java.util.Optional;

/** Future committed completion/zero-confirmation store; read within the caller's DB transaction.
 * Evidence must originate from explicit completion, not counts, due status or inferred missing records.
 * No empty/default implementation is installed. This is not an HTTP request input or a common port.
 */
public interface PaymentObservationSource {
    Optional<Confirmation> find(Scope scope);
}
