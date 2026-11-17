package com.loanrun.user.repository;

import com.loanrun.user.domain.DemoUser;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DemoUserRepository extends JpaRepository<DemoUser, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from DemoUser u where u.id = :id")
    Optional<DemoUser> findLocked(@Param("id") long id);
}
