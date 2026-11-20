package com.loanrun.payment.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.payment.service.PaymentMonthlySummaryService;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentMonthlySummaryController {
    private final CurrentUserProvider user;private final PaymentMonthlySummaryService service;
    public PaymentMonthlySummaryController(CurrentUserProvider user,PaymentMonthlySummaryService service){this.user=user;this.service=service;}
    @GetMapping("/api/payments/monthly-summary/")
    public ApiResponse<JsonNode> summary(@RequestParam MultiValueMap<String,String> q) {
        for(var e:q.entrySet())if(!java.util.Set.of("from_month","to_month","loan_id").contains(e.getKey())||e.getValue().size()!=1)throw PaymentMonthlySummaryService.range();
        return ApiResponse.success("PAYMENT_MONTHLY_SUMMARY_SUCCESS","구간 기록·월별 상환 통계 정보를 조회했습니다.",service.summary(user.currentUserId(),q.getFirst("from_month"),q.getFirst("to_month"),q.getFirst("loan_id")));
    }
}
