package com.loanrun.payment.service;

import com.loanrun.payment.dto.PaymentDetail;
import com.loanrun.payment.repository.PaymentReadRepository;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

@Service
public class PaymentReadService {
    private final PaymentReadRepository payments;
    public PaymentReadService(PaymentReadRepository payments) { this.payments=payments; }

    public PaymentDetail detail(long userId,long paymentId) {
        var source=payments.detail(userId,paymentId);
        var p=source.payment();
        var allocations=source.allocations().stream().map(a->new PaymentDetail.DueAllocation(a.dueItemId(),
            Math.addExact(a.principal(),a.interest()),Math.max(0,ChronoUnit.DAYS.between(a.dueDate(),p.paidAt())))).toList();
        var first=allocations.isEmpty()?null:allocations.getFirst();
        return new PaymentDetail(p.id(),p.loanId(),p.paidAt(),p.amount(),p.kind(),first==null?null:first.dueItemId(),
            p.regularPrincipal(),p.interest(),p.extraPrincipal(),p.createdAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
            p.fee(),p.savingsUsed(),!p.paidAt().isBefore(source.snapshotDate()),first==null?null:first.delayDays(),allocations);
    }
}
