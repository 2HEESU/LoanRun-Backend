package com.loanrun.common.contract;

import com.loanrun.common.contract.FinanceModels.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Team 1 implements. Every query is owner-scoped; a foreign/missing loan throws LOAN_NOT_FOUND. */
public interface LoanQueryPort {
    LoanTerms terms(long userId, long loanId, LocalDate asOf);
    List<LoanTerms> activeLoans(long userId, LocalDate asOf);
    List<LoanTerms> historicalLoans(long userId, DateRange range);
    DueSummary dueSummary(long userId, YearMonth month);
    PaymentSummary paymentSummary(long userId, YearMonth month);
    SavingSummary savingSummary(long userId, YearMonth month);
    List<HistoryMonth> history(long userId, DateRange range);
    List<ExportLoan> exportLoans(long userId);
    List<ExportPayment> exportPayments(long userId, DateRange range);
}
