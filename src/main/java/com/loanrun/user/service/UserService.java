package com.loanrun.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.IncomeType;
import com.loanrun.common.contract.FinanceProfileQueryPort;
import com.loanrun.common.contract.RevisionPort;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.validation.JsonPatch;
import com.loanrun.user.dto.UserResponse;
import com.loanrun.user.repository.DemoUserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final DemoUserRepository users;
    private final RevisionPort revisions;
    private final ObjectProvider<FinanceProfileQueryPort> profiles;
    public UserService(DemoUserRepository users, RevisionPort revisions, ObjectProvider<FinanceProfileQueryPort> profiles) {
        this.users = users; this.revisions = revisions; this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public UserResponse get(long userId) { return UserResponse.from(users.findById(userId).orElseThrow(ApiException::unavailable)); }

    @Transactional
    public UserResponse patch(long userId, JsonNode patch) {
        JsonPatch.fields(patch, "name", "occupation", "income_type");
        var user = users.findLocked(userId).orElseThrow(ApiException::unavailable);
        if (patch.has("name")) user.setName(JsonPatch.text(patch, "name", false, 50));
        if (patch.has("occupation")) user.setOccupation(JsonPatch.text(patch, "occupation", true, 50));
        if (patch.has("income_type")) {
            IncomeType incomeType = JsonPatch.enumeration(patch, "income_type", IncomeType.class);
            var profile = profiles.getIfAvailable();
            if (profile != null) {
                if (profile.incomeType(userId).filter(type -> type != incomeType).isPresent()) {
                    throw ApiException.validation("income_type", "기본 재정정보에서 소득 유형과 필수값을 함께 변경해 주세요.");
                }
            }
            user.setIncomeType(incomeType);
        }
        if (!patch.isEmpty()) revisions.bump(userId, "PROFILE_CHANGED");
        return UserResponse.from(user);
    }
}
