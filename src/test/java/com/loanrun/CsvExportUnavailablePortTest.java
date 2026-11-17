package com.loanrun;

import static org.assertj.core.api.Assertions.*;

import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.setting.service.CsvExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class CsvExportUnavailablePortTest {
    @Test
    void absentTeamOnePortsFailExplicitlyInsteadOfReturningEmptySuccessfulData() {
        var beans = new StaticListableBeanFactory();
        var csv = new CsvExportService(beans.getBeanProvider(LoanQueryPort.class));
        assertThatThrownBy(() -> csv.export(1, "csv", null, null)).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
}
