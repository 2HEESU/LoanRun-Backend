package com.loanrun.repaymentsaving.dto;

import java.math.BigDecimal;

public record SavingCreated(SavingList.Item saving,long loanId,long savingsBalance,long requiredMonthlySaving,
    long plannedSavingAmount,long actualSavingAmount,BigDecimal savingProgressPct,long remainingPrincipal,boolean reportStale) {}
