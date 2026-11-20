package com.loanrun.repaymentplan.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.common.error.ApiException;
import com.loanrun.repaymentplan.service.PlanReadService;
import java.util.Set;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repayment-plans")
public class PlanController {
    private final CurrentUserProvider user;private final PlanReadService reads;
    public PlanController(CurrentUserProvider user,PlanReadService reads){this.user=user;this.reads=reads;}
    @GetMapping("/")
    public ApiResponse<JsonNode> list(@RequestParam MultiValueMap<String,String> query) {
        for(var e:query.entrySet())if(!Set.of("loan_id","as_of").contains(e.getKey())||e.getValue().size()!=1)throw ApiException.validation(e.getKey(),"조회 조건을 확인해 주세요.");
        return ApiResponse.success("REPAYMENT_PLAN_LIST_SUCCESS","상환 계획 정보를 조회했습니다.",reads.list(user.currentUserId(),user.currentPlanType(),query.getFirst("loan_id"),query.getFirst("as_of")));
    }
}
