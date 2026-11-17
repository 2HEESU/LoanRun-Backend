package com.loanrun.common.contract;

public interface CurrentUserProvider {
    long currentUserId();
    PlanType currentPlanType();
    void requirePro();
}
