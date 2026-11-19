package com.loanrun.payment.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.payment.service.PaymentDeleteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class PaymentDeleteController {
    private final CurrentUserProvider user;private final PaymentDeleteService service;
    public PaymentDeleteController(CurrentUserProvider user,PaymentDeleteService service){this.user=user;this.service=service;}
    @DeleteMapping("/api/payments/{payment_id:[0-9]+}/")public ResponseEntity<JsonNode> delete(@PathVariable("payment_id")long id,@RequestBody(required=false)String body,@RequestHeader(value="Idempotency-Key",required=false)String key){var r=service.delete(user.currentUserId(),id,body,key);return ResponseEntity.status(r.status()).body(r.body());}
}
