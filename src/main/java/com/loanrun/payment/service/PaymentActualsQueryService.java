package com.loanrun.payment.service;

import com.loanrun.common.contract.FinanceModels.DateRange;
import com.loanrun.payment.read.*;
import com.loanrun.payment.repository.PaymentActualsRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PaymentActualsQueryService {
    private final PaymentActualsRepository payments;
    public PaymentActualsQueryService(PaymentActualsRepository payments) { this.payments=payments; }
    public PaymentActualTotals aggregate(long userId,DateRange range,Long loanId) {
        return payments.aggregate(userId,loanId,range.startInclusive(),range.endInclusive(),null);
    }
    public List<PaymentActualMonth> monthly(long userId,DateRange range,Long loanId) { return payments.monthly(userId,range,loanId); }
}
