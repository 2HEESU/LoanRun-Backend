package com.loanrun.user.dto;

import com.loanrun.common.contract.IncomeType;
import com.loanrun.common.contract.PlanType;
import com.loanrun.user.domain.DemoUser;

public record UserResponse(long userId, String email, String name, String occupation,
        IncomeType incomeType, boolean onboardingCompleted, PlanType planType) {
    public static UserResponse from(DemoUser user) {
        return new UserResponse(user.getId(), "demo-" + user.getId() + "@example.invalid", user.getName(),
            user.getOccupation(), user.getIncomeType(), user.isOnboardingCompleted(), user.getPlanType());
    }
}
