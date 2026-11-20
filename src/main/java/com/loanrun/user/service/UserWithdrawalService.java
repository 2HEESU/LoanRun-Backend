package com.loanrun.user.service;

import com.loanrun.common.error.ApiException;
import com.loanrun.user.repository.DemoUserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Removes one anonymous owner's records in dependency order under the shared user row lock. */
@Service
public class UserWithdrawalService {
    private final DemoUserRepository users;
    private final JdbcTemplate jdbc;

    public UserWithdrawalService(DemoUserRepository users, JdbcTemplate jdbc) {
        this.users = users;
        this.jdbc = jdbc;
    }

    @Transactional
    public void withdraw(long userId) {
        users.findLocked(userId).orElseThrow(ApiException::unavailable);
        jdbc.queryForObject("SELECT set_config('loanrun.withdraw_user_id', ?, true)", String.class, Long.toString(userId));
        jdbc.update("DELETE FROM strategy_candidates WHERE report_id IN (SELECT id FROM report_jobs WHERE user_id=?)", userId);
        jdbc.update("DELETE FROM monthly_allocations WHERE budget_id IN (SELECT id FROM monthly_budgets WHERE user_id=?)", userId);
        for (String table : new String[] {
            "report_jobs", "report_attempts", "comparison_settings", "risk_assessment_requests",
            "risk_assessment_versions", "finance_change_events", "finance_input_versions",
            "payment_allocations", "payments", "due_items", "repayment_savings",
            "repayment_plan_versions", "payment_due_archives", "loan_balance_snapshots",
            "loan_rate_histories", "loans", "income_entries",
            "monthly_budgets", "finance_profiles", "plan_application_events",
            "idempotency_records", "notification_settings"
        }) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", userId);
        }
        jdbc.update("DELETE FROM demo_users WHERE id=?", userId);
    }
}
