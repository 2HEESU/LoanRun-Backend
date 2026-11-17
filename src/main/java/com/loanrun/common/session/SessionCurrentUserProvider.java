package com.loanrun.common.session;

import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.common.contract.PlanType;
import com.loanrun.common.error.ApiException;
import com.loanrun.user.repository.DemoUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
public class SessionCurrentUserProvider implements CurrentUserProvider {
    private final HttpServletRequest request;
    private final AnonymousUserService anonymousUsers;
    private final DemoUserRepository users;
    private Long userId;

    public SessionCurrentUserProvider(HttpServletRequest request, AnonymousUserService anonymousUsers, DemoUserRepository users) {
        this.request = request; this.anonymousUsers = anonymousUsers; this.users = users;
    }

    @Override
    public long currentUserId() {
        if (userId == null) {
            HttpSession session = request.getSession(true);
            userId = anonymousUsers.resolve(session.getId());
            session.setAttribute("demo_user_id", userId);
        }
        return userId;
    }

    @Override
    public PlanType currentPlanType() {
        return users.findById(currentUserId()).orElseThrow(ApiException::unavailable).getPlanType();
    }

    @Override
    public void requirePro() {
        if (currentPlanType() != PlanType.PRO) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PRO_REQUIRED", "PRO 이용자만 사용할 수 있습니다.");
        }
    }
}
