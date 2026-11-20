package com.loanrun.loan.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.loan.dto.LoanPreview;
import com.loanrun.loan.service.LoanPreviewService;
import com.loanrun.loan.service.LoanRegistrationService;
import com.loanrun.loan.service.LoanListService;
import com.loanrun.loan.dto.LoanList;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/loans")
public class LoanController {
    private final CurrentUserProvider currentUser;
    private final LoanPreviewService service;
    private final LoanRegistrationService registration;
    private final LoanListService lists;
    public LoanController(CurrentUserProvider currentUser, LoanPreviewService service, LoanRegistrationService registration, LoanListService lists) { this.currentUser = currentUser; this.service = service; this.registration = registration; this.lists=lists; }
    @GetMapping("/")
    public ApiResponse<LoanList> list(@RequestParam(required=false) String status) {
        return ApiResponse.success("LOAN_LIST_SUCCESS","보유 대출 목록 정보를 조회했습니다.",lists.list(currentUser.currentUserId(),status==null?"ACTIVE":status));
    }
    @PostMapping("/")
    public ResponseEntity<JsonNode> create(@RequestBody JsonNode body, @RequestHeader(value="Idempotency-Key",required=false) String key) {
        var result=registration.create(currentUser.currentUserId(),body,key);
        return ResponseEntity.status(result.status()).body(result.body());
    }
    @PostMapping("/preview/")
    public ApiResponse<LoanPreview> preview(@RequestBody JsonNode body) {
        return ApiResponse.success("LOAN_PREVIEW_SUCCESS", "기본 월 상환액을 계산했습니다.", service.preview(currentUser.currentUserId(), body));
    }
}
