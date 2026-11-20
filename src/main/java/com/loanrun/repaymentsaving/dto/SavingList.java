package com.loanrun.repaymentsaving.dto;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public record SavingList(long loanId,long savingsBalance,long requiredMonthlySaving,long plannedSavingAmount,
    long actualSavingAmount,BigDecimal savingProgressPct,long remainingPrincipal,List<Item> results) {
    public SavingList {results=List.copyOf(results);}
    public record Item(long savingId,long loanId,LocalDate savedAt,long amount,boolean editable,OffsetDateTime createdAt) {}
}
