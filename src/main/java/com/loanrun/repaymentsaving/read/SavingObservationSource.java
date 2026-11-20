package com.loanrun.repaymentsaving.read;

import com.loanrun.repaymentsaving.read.SavingObservation.*;
import java.util.Optional;

/** Explicit committed saving-month completion evidence, read in the caller's transaction.
 * Neither payment completion nor missing saving rows prove it. No default source/writer is installed.
 */
public interface SavingObservationSource {
    Optional<Confirmation> find(Scope scope);
}
