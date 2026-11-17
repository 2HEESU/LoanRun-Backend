package com.loanrun.common.revision;

import com.loanrun.common.contract.RevisionPort;
import com.loanrun.common.contract.UserMutationLock;
import com.loanrun.common.error.ApiException;
import com.loanrun.user.repository.DemoUserRepository;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRevisionService implements RevisionPort, UserMutationLock {
    private final DemoUserRepository users;
    private final Clock clock;
    public UserRevisionService(DemoUserRepository users, Clock clock) { this.users = users; this.clock = clock; }

    @Override @Transactional(readOnly = true)
    public long current(long userId) { return users.findById(userId).orElseThrow(this::missing).getRevision(); }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long userId) { users.findLocked(userId).orElseThrow(this::missing); }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public long bump(long userId, String reason) {
        var user = users.findLocked(userId).orElseThrow(this::missing);
        user.changed(clock.instant());
        return user.getRevision();
    }

    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."); }
}
