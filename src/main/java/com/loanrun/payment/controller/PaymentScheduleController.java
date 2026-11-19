package com.loanrun.payment.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.payment.service.PaymentScheduleService;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
@RestController
public class PaymentScheduleController {
    private final CurrentUserProvider user;private final PaymentScheduleService service;
    public PaymentScheduleController(CurrentUserProvider user,PaymentScheduleService service){this.user=user;this.service=service;}
    @GetMapping("/api/payments/schedule/")public ApiResponse<JsonNode> schedule(@RequestParam MultiValueMap<String,String> q){for(var e:q.entrySet())if(!java.util.Set.of("month","loan_id").contains(e.getKey())||e.getValue().size()!=1)throw PaymentScheduleService.invalidMonth();return ApiResponse.success("PAYMENT_SCHEDULE_SUCCESS","월별 상환 일정 및 납부 상태 정보를 조회했습니다.",service.read(user.currentUserId(),q.getFirst("month"),q.getFirst("loan_id")));}
}
