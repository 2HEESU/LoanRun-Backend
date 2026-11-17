package com.loanrun.common.contract;

public interface RevisionPort {
    long current(long userId);
    long bump(long userId, String reason);
}
