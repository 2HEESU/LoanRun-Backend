package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.RevisionPort;
import com.loanrun.common.error.ApiException;
import com.loanrun.setting.service.SubscriptionService;
import com.loanrun.user.repository.DemoUserRepository;
import org.junit.jupiter.api.Test;

class SubscriptionServiceTest {
    @Test
    void disabledDemoModeRejectsChangesBeforeTouchingUserData() throws Exception {
        var users = mock(DemoUserRepository.class);
        var revisions = mock(RevisionPort.class);
        var service = new SubscriptionService(users, revisions, false);
        var body = new ObjectMapper().readTree("{\"plan_type\":\"PRO\"}");
        assertThatThrownBy(() -> service.patch(1, body)).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("DEMO_MODE_REQUIRED"));
        verifyNoInteractions(users, revisions);
    }
}
