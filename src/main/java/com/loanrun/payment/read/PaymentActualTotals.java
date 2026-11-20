package com.loanrun.payment.read;

/** Exact sums of stored payment rows, not a confirmed/observed monthly performance projection. */
public record PaymentActualTotals(long paymentCount,long regularCount,long extraPaymentCount,long extraPrincipalCount,
    long amount,long regularPrincipal,long extraPrincipal,long interest,long fee,long savingsUsed,long cashPaid) {
    public PaymentActualTotals {
        if(paymentCount<0||regularCount<0||extraPaymentCount<0||extraPrincipalCount<0||amount<0||regularPrincipal<0||extraPrincipal<0||interest<0||fee<0||savingsUsed<0||cashPaid<0)
            throw new IllegalArgumentException("Negative actual totals");
        long principal=Math.addExact(regularPrincipal,extraPrincipal);
        if(paymentCount!=Math.addExact(regularCount,extraPaymentCount)||extraPrincipalCount>paymentCount||
            amount!=Math.addExact(Math.addExact(principal,interest),fee)||savingsUsed>principal||cashPaid!=amount-savingsUsed||
            (paymentCount==0)!=(amount==0))throw new IllegalArgumentException("Inconsistent actual totals");
    }
}
