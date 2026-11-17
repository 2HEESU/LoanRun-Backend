package com.loanrun.common.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/** Immutable internal projections. Monetary amounts are KRW; nullable values mean unknown. */
public final class FinanceModels {
    private FinanceModels() {}
    public enum RepaymentMethod { EQUAL_PAYMENT, EQUAL_PRINCIPAL, GRACE_INSTALLMENT, BULLET }
    public enum RateType { FIXED, VARIABLE }
    public enum LoanStatus { ACTIVE, PAID, DELETED }
    public enum LoanType { CREDIT, MORTGAGE, JEONSE, STUDENT, AUTO, OTHER }
    public enum PrepaymentPolicy { KEEP_PAYMENT, KEEP_MATURITY }
    public enum FeeMethod { FIXED, LINEAR, NONE, UNKNOWN }
    public enum AllocationType { EXTRA_PAYMENT, REPAYMENT_SAVING }

    public record DateRange(LocalDate startInclusive, LocalDate endInclusive) {
        public DateRange {
            if (startInclusive == null || endInclusive == null || endInclusive.isBefore(startInclusive))
                throw new IllegalArgumentException("Invalid date range");
        }
    }

    public record LoanTerms(long loanId, long userId, String productName, LoanType loanType,
        long principalOriginal, long outstanding, LocalDate snapshotDate, BigDecimal annualRate,
        RateType rateType, RepaymentMethod method, LocalDate graceEnd, RepaymentMethod postGraceMethod,
        LocalDate nextDueDate, int dueDay, LocalDate maturityDate, Long contractualNextAmount,
        Long baseAnchorAmount, Long fixedPrincipal, PrepaymentPolicy prepaymentPolicy,
        FeeMethod feeMethod, BigDecimal feeRate, LocalDate feeStartDate, LocalDate feeEndDate,
        long initialSavingBalance, long currentSavingBalance, LoanStatus status, LocalDate rateResetDate) {}

    /** cashAmount includes estimated fees, savingAmount never reduces loan principal. */
    public record PlannedAmount(long loanId, AllocationType allocationType, long cashAmount, Integer priority) {}

    public record SimulationInput(List<LoanTerms> terms, LocalDate asOf, int horizonMonths,
        Map<YearMonth, List<PlannedAmount>> monthlyAmounts, Map<Long, BigDecimal> rateOverrides,
        LocalDate targetDate, String allocationRule) {
        public SimulationInput {
            terms = List.copyOf(terms);
            monthlyAmounts = monthlyAmounts.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> List.copyOf(e.getValue())));
            rateOverrides = Map.copyOf(rateOverrides);
            if (horizonMonths < 1 || horizonMonths > 1200) throw new IllegalArgumentException("Invalid simulation horizon");
        }
    }

    public record SimulationRow(LocalDate date, long loanId, long scheduledPrincipal, long interest,
        Long fee, long extraPrincipal, long extraCash, long saving, long balance, long remainingSaving) {}

    public record SimulationResult(List<SimulationRow> rows, LocalDate projectedPayoffDate,
        Long totalPrincipal, Long totalInterest, Long totalFee, Long totalCash, boolean insufficient,
        String feeComparisonBasis, List<String> warnings) {
        public SimulationResult { rows = List.copyOf(rows); warnings = List.copyOf(warnings); }
    }

    public record TargetInput(SimulationInput baseline, long loanId, LocalDate targetDate) {}
    public record TargetResult(Long minimumMonthlyExtraCash, boolean feasible, List<String> warnings) {
        public TargetResult { warnings = List.copyOf(warnings); }
    }

    public record DueSummary(YearMonth month, long scheduledCash, long previousOutstandingCash,
        long paidRequired, long outstandingRequired, long savingsUsedForDue, LocalDate nextDueDate,
        int totalCount, int paidCount, int partialCount, int confirmedUnpaidCount, int unrecordedCount) {}

    public record PaymentSummary(YearMonth month, long regularPrincipal, long extraPrincipal,
        long interest, long fee, long cashPaid, long savingsUsed, int extraCount, boolean observed,
        int missedCount, int partialCount, Map<Long, Long> extraCashByLoan) {
        public PaymentSummary { extraCashByLoan = Map.copyOf(extraCashByLoan); }
    }

    public record SavingSummary(YearMonth month, long additions, long initialBalance, long withdrawals,
        long closingBalance, boolean observed, long requiredAmount, Map<Long, Long> additionsByLoan) {
        public SavingSummary { additionsByLoan = Map.copyOf(additionsByLoan); }
    }

    public record HistoryMonth(YearMonth month, long openingBalance, long registeredBalance,
        long regularPrincipal, long extraPrincipal, long interest, long fees, int extraCount,
        long savingAdditions, long savingsUsed, boolean observed) {}

    public record ExportLoan(long loanId, String productName, long remainingPrincipal) {}
    public record ExportPayment(long loanId, String productName, LocalDate paidAt, long amount,
        long principalAmount, long interestAmount, long extraAmount, long feeAmount, long savingsUsedAmount) {}

    public record PlanSnapshot(long loanId, boolean configured, LocalDate targetPayoffDate,
        LocalDate projectedPayoffDate, long monthlyExtraCash, long monthlySaving, long requiredMonthlySaving,
        String mode, String preference, LocalDate effectiveDate, long version) {}

    public record OverallState(boolean exists, String strategyCode, String strategyType, Instant appliedAt) {}

    public record BudgetCapacity(YearMonth month, Long availableCash, Long recurringCap, long carryIn,
        Long allocationCapacity, long usedExtraCash, long usedSaving, Long remainingAllocatable,
        long reserveAmount, Long deficit, String incomeStatus, String settlementStatus, List<String> warnings) {
        public BudgetCapacity { warnings = List.copyOf(warnings); }
    }

    public record FinanceSnapshot(YearMonth month, IncomeType incomeType, Long planningIncome, Long actualIncome,
        Long fixedEssentialExpenses, Long variableEssentialExpenses, Long discretionaryExpenses,
        Long actualDiscretionaryExpenses, Long discretionaryLimit, Long savings, Long emergencyFund,
        boolean expensesConfirmed, String source) {}

    public record StrategyCandidate(long reportId, String strategyVersion, String code, String type,
        long dataRevision, Instant computedAt, List<PlannedAmount> allocations, SimulationResult simulation,
        boolean feasible, List<String> warnings) {
        public StrategyCandidate { allocations = List.copyOf(allocations); warnings = List.copyOf(warnings); }
    }

    public record EligibleProduct(long productId, long institutionId, String institutionName,
        String name, LoanType loanType, List<RepaymentMethod> repaymentMethods) {
        public EligibleProduct { repaymentMethods = List.copyOf(repaymentMethods); }
    }
}
