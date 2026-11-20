package com.loanrun.finance.engine;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.FinanceModels.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PrincipalProjectionValidationTest {
    final DefaultRepaymentEngine engine = new DefaultRepaymentEngine();
    final YearMonth month = YearMonth.of(2026, 1);
    SimulationInput input(LoanFixture f, List<PlannedAmount> amounts, String rule) {
        return new SimulationInput(List.of(f.terms()), LocalDate.of(2026, 1, 1), 12,
            Map.of(month, amounts), Map.of(), null, rule);
    }
    @Test void foreignLoanAndNegativePrincipalAreRejected() {
        var input = input(new LoanFixture(), List.of(), "KEEP_PLANS");
        assertThatThrownBy(() -> engine.simulateWithExtraPrincipal(input, Map.of(month, Map.of(2L, 10L))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.simulateWithExtraPrincipal(input, Map.of(month, Map.of(1L, -1L))))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void bulletCannotReceivePrincipalProjection() {
        var input = input(new LoanFixture(RepaymentMethod.BULLET), List.of(), "KEEP_PLANS");
        assertThatThrownBy(() -> engine.simulateWithExtraPrincipal(input, Map.of(month, Map.of(1L, 10L))))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sameLoanCannotReceiveBothCashAndPrincipalInOneMonth() {
        var input = input(new LoanFixture(), List.of(new PlannedAmount(1, AllocationType.EXTRA_PAYMENT, 100, null)), "KEEP_PLANS");
        assertThatThrownBy(() -> engine.simulateWithExtraPrincipal(input, Map.of(month, Map.of(1L, 100L))))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void principalCannotBePooledAsCashByStrategyRules() {
        var input = input(new LoanFixture(), List.of(), "PARALLEL");
        assertThatThrownBy(() -> engine.simulateWithExtraPrincipal(input, Map.of(month, Map.of(1L, 100L))))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void emptyPrincipalInputPreservesTheExistingCashSimulationExactly() {
        var input = input(new LoanFixture(), List.of(new PlannedAmount(1, AllocationType.EXTRA_PAYMENT, 100, null)), "KEEP_PLANS");
        assertThat(engine.simulateWithExtraPrincipal(input, Map.of())).isEqualTo(engine.simulate(input));
    }
}
