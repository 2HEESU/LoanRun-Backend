package com.loanrun.payment.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.payment.service.PaymentUpdateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class PaymentUpdateController {
    private final CurrentUserProvider user;private final PaymentUpdateService service;
    public PaymentUpdateController(CurrentUserProvider user,PaymentUpdateService service){this.user=user;this.service=service;}
    @PatchMapping("/api/payments/{payment_id:[0-9]+}/")public ResponseEntity<JsonNode> update(@PathVariable("payment_id")long id,@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false)String key){var r=service.update(user.currentUserId(),id,body,key);return ResponseEntity.status(r.status()).body(r.body());}
}
