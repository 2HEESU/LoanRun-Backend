package com.loanrun.repaymentsaving.controller;

import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.repaymentsaving.dto.SavingList;
import com.loanrun.repaymentsaving.service.SavingListService;
import com.loanrun.repaymentsaving.service.SavingCreateService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repayment-savings")
public class SavingController {
    private final CurrentUserProvider users;
    private final SavingListService savings;
    private final SavingCreateService creates;
    public SavingController(CurrentUserProvider users,SavingListService savings,SavingCreateService creates){this.users=users;this.savings=savings;this.creates=creates;}
    @PostMapping("/")
    public ResponseEntity<JsonNode> create(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var result=creates.create(users.currentUserId(),body,key);
        return ResponseEntity.status(result.status()).body(result.body());
    }
    @GetMapping("/")
    public ApiResponse<SavingList> list(@RequestParam MultiValueMap<String,String> query) {
        return ApiResponse.success("SAVING_LIST_SUCCESS","상환자금 적립 내역 조회 성공",savings.list(users.currentUserId(),query));
    }
}
