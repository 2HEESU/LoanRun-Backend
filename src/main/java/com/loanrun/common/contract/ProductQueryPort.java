package com.loanrun.common.contract;

import com.loanrun.common.contract.FinanceModels.EligibleProduct;

public interface ProductQueryPort {
    EligibleProduct findEligibleProduct(long productId);
}
