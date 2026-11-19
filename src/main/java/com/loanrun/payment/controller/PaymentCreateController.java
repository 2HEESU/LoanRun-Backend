package com.loanrun.payment.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.payment.service.PaymentCreateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class PaymentCreateController {
    private final CurrentUserProvider user;private final PaymentCreateService service;
    public PaymentCreateController(CurrentUserProvider user,PaymentCreateService service){this.user=user;this.service=service;}
    @PostMapping("/api/payments/")public ResponseEntity<JsonNode> create(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key){var r=service.create(user.currentUserId(),body,key);return ResponseEntity.status(r.status()).body(r.body());}
}
