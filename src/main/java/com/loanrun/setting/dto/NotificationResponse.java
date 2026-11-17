package com.loanrun.setting.dto;
public record NotificationResponse(boolean paymentDue, boolean reportUpdate, boolean rateReset, boolean graceEnd) {}
