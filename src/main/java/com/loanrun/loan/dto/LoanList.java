package com.loanrun.loan.dto;

import java.time.LocalDate;
import java.util.List;

public record LoanList(List<LoanCreated> loans,long totalRemainingPrincipal,long totalMonthlyDue,
    LocalDate estimatedPayoffDate,int count) {
    public LoanList {loans=List.copyOf(loans);}
}
