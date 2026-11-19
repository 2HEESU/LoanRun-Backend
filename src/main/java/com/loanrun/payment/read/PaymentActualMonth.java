package com.loanrun.payment.read;

import java.time.YearMonth;

/** A month/loan group that actually has persisted payments. Missing months are not emitted as zero. */
public record PaymentActualMonth(YearMonth month,long loanId,PaymentActualTotals totals) {}
