package com.loanrun.repaymentplan.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.common.error.ApiException;
import com.loanrun.repaymentplan.service.OverallPlanService;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
@RestController
public class OverallPlanController {
    private final CurrentUserProvider user;private final OverallPlanService service;
    public OverallPlanController(CurrentUserProvider user,OverallPlanService service){this.user=user;this.service=service;}
    @GetMapping("/api/plans/overall/")public ApiResponse<JsonNode> overall(@RequestParam MultiValueMap<String,String> q){user.requirePro();if(!q.isEmpty())throw ApiException.validation("query","조회 조건을 입력할 수 없습니다.");return ApiResponse.success("OVERALL_PLAN_SUCCESS","전체 상환 계획을 조회했습니다.",service.read(user.currentUserId()));}
}
