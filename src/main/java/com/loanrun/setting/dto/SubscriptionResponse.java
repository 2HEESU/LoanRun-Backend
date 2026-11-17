package com.loanrun.setting.dto;

import com.loanrun.common.contract.PlanType;
import java.util.List;

public record SubscriptionResponse(PlanType planType, List<String> proFeatures, boolean billingEnabled, List<String> freeFeatures) {
    public static SubscriptionResponse of(PlanType planType) {
        return new SubscriptionResponse(planType, List.of("RISK_ANALYSIS", "AI_REPORT", "OVERALL_STRATEGY_PLAN"), false,
            List.of("LOANS", "INDIVIDUAL_PLANS", "BUDGETS", "PAYMENTS", "DASHBOARD"));
    }
}
