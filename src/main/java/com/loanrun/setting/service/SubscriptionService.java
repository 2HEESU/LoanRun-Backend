package com.loanrun.setting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.PlanType;
import com.loanrun.common.contract.RevisionPort;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.validation.JsonPatch;
import com.loanrun.setting.dto.SubscriptionResponse;
import com.loanrun.user.repository.DemoUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {
    private final DemoUserRepository users;
    private final RevisionPort revisions;
    private final boolean demoMode;
    public SubscriptionService(DemoUserRepository users, RevisionPort revisions, @Value("${loanrun.demo-mode}") boolean demoMode) {
        this.users = users; this.revisions = revisions; this.demoMode = demoMode;
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse get(long userId) {
        return SubscriptionResponse.of(users.findById(userId).orElseThrow(ApiException::unavailable).getPlanType());
    }

    @Transactional
    public SubscriptionResponse patch(long userId, JsonNode patch) {
        if (!demoMode) throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_MODE_REQUIRED", "시연 모드에서만 변경할 수 있습니다.");
        JsonPatch.fields(patch, "plan_type");
        PlanType plan = JsonPatch.enumeration(patch, "plan_type", PlanType.class);
        var user = users.findLocked(userId).orElseThrow(ApiException::unavailable);
        if (user.getPlanType() != plan) {
            user.setPlanType(plan);
            revisions.bump(userId, "SUBSCRIPTION_CHANGED");
        }
        return SubscriptionResponse.of(user.getPlanType());
    }
}
