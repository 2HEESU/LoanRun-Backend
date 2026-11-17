package com.loanrun.common.contract;

/** Caller must hold a transaction; acquire before any user-scoped mutation/read-modify-write. */
public interface UserMutationLock {
    void lock(long userId);
}
