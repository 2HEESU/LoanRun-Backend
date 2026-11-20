package com.loanrun.loan.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.loan.service.LoanRateUpdateService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class LoanRateUpdateController {
    private final CurrentUserProvider user;private final LoanRateUpdateService service;
    public LoanRateUpdateController(CurrentUserProvider user,LoanRateUpdateService service){this.user=user;this.service=service;}
    @PostMapping("/api/loans/{loan_id}/rate-history/")
    public ResponseEntity<ApiResponse<JsonNode>> update(@PathVariable("loan_id")long id,@RequestBody JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("LOAN_RATE_UPDATE_SUCCESS","대출 금리 변경 등록 처리를 완료했습니다.",service.update(user.currentUserId(),id,user.currentPlanType(),body)));
    }
}
