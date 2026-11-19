package com.loanrun.repaymentplan.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.repaymentplan.service.PlanSaveService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class PlanSaveController {
    private final CurrentUserProvider user;private final PlanSaveService service;
    public PlanSaveController(CurrentUserProvider user,PlanSaveService service){this.user=user;this.service=service;}
    @PutMapping("/api/repayment-plans/") public ResponseEntity<JsonNode> save(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key){var r=service.save(user.currentUserId(),user.currentPlanType(),body,key);return ResponseEntity.status(r.status()).body(r.body());}
}
