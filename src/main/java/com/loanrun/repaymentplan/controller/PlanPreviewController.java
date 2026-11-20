package com.loanrun.repaymentplan.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.repaymentplan.service.PlanPreviewService;
import org.springframework.web.bind.annotation.*;

@RestController
public class PlanPreviewController {
    private final CurrentUserProvider user;private final PlanPreviewService service;
    public PlanPreviewController(CurrentUserProvider user,PlanPreviewService service){this.user=user;this.service=service;}
    @PostMapping("/api/repayment-plans/preview/")
    public ApiResponse<JsonNode> preview(@RequestBody JsonNode body) {
        return ApiResponse.success("REPAYMENT_PLAN_PREVIEW_SUCCESS","상환 계획을 계산했습니다.",service.preview(user.currentUserId(),user.currentPlanType(),body));
    }
}
