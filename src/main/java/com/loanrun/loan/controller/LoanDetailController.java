package com.loanrun.loan.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.service.LoanDetailService;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class LoanDetailController {
    private final CurrentUserProvider user;private final LoanDetailService service;
    public LoanDetailController(CurrentUserProvider user,LoanDetailService service){this.user=user;this.service=service;}
    @GetMapping("/api/loans/{loan_id:[0-9]+}/")
    public ApiResponse<JsonNode> detail(@PathVariable("loan_id")long id,@RequestParam MultiValueMap<String,String> query) {
        if(!query.isEmpty())throw ApiException.validation("query","조회 조건을 확인해 주세요.");
        return ApiResponse.success("LOAN_DETAIL_SUCCESS","대출 상세 정보를 조회했습니다.",service.detail(user.currentUserId(),id,user.currentPlanType()));
    }
}
