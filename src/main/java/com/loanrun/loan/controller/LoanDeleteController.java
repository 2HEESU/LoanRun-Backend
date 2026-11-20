package com.loanrun.loan.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.loan.service.LoanDeleteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/loans")
public class LoanDeleteController {
    private final CurrentUserProvider users;
    private final LoanDeleteService deletes;
    public LoanDeleteController(CurrentUserProvider users,LoanDeleteService deletes){this.users=users;this.deletes=deletes;}
    @DeleteMapping("/{loan_id}/")
    public ResponseEntity<JsonNode> delete(@PathVariable("loan_id") long loanId,@RequestBody(required=false) String body,
        @RequestHeader(value="Idempotency-Key",required=false) String key) {
        var result=deletes.delete(users.currentUserId(),loanId,body,key);
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
