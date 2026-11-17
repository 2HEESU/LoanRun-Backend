package com.loanrun.setting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.UserMutationLock;
import com.loanrun.common.validation.JsonPatch;
import com.loanrun.setting.dto.NotificationResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final UserMutationLock locks;
    public NotificationService(JdbcTemplate jdbc, UserMutationLock locks) { this.jdbc = jdbc; this.locks = locks; }

    @Transactional(readOnly = true)
    public NotificationResponse get(long userId) {
        var values = jdbc.query("SELECT payment_due, report_update, rate_reset, grace_end FROM notification_settings WHERE user_id = ?",
            (rs, i) -> new NotificationResponse(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4)), userId);
        return values.isEmpty() ? new NotificationResponse(true, true, true, true) : values.getFirst();
    }

    @Transactional
    public NotificationResponse patch(long userId, JsonNode patch) {
        JsonPatch.fields(patch, "payment_due", "report_update", "rate_reset", "grace_end");
        locks.lock(userId);
        var existing = get(userId);
        var updated = new NotificationResponse(
            patch.has("payment_due") ? JsonPatch.bool(patch, "payment_due") : existing.paymentDue(),
            patch.has("report_update") ? JsonPatch.bool(patch, "report_update") : existing.reportUpdate(),
            patch.has("rate_reset") ? JsonPatch.bool(patch, "rate_reset") : existing.rateReset(),
            patch.has("grace_end") ? JsonPatch.bool(patch, "grace_end") : existing.graceEnd());
        jdbc.update("""
            INSERT INTO notification_settings(user_id, payment_due, report_update, rate_reset, grace_end)
            VALUES (?, ?, ?, ?, ?) ON CONFLICT(user_id) DO UPDATE SET payment_due=excluded.payment_due,
            report_update=excluded.report_update, rate_reset=excluded.rate_reset, grace_end=excluded.grace_end
            """, userId, updated.paymentDue(), updated.reportUpdate(), updated.rateReset(), updated.graceEnd());
        return updated;
    }
}
