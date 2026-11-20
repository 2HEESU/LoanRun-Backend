package com.loanrun.loan.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.loan.dto.LoanCreated;
import com.loanrun.loan.service.LoanPatchService;
import org.springframework.web.bind.annotation.*;

@RestController
public class LoanPatchController {
    private final CurrentUserProvider user;private final LoanPatchService service;
    public LoanPatchController(CurrentUserProvider user,LoanPatchService service){this.user=user;this.service=service;}
    @PatchMapping("/api/loans/{loan_id:[0-9]+}/")
    public ApiResponse<LoanCreated> update(@PathVariable("loan_id")long id,@RequestBody JsonNode body) {
        return ApiResponse.success("LOAN_UPDATE_SUCCESS","대출 정보를 수정했습니다.",service.update(user.currentUserId(),id,body));
    }
}
