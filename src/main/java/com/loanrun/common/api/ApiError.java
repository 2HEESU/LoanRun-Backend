package com.loanrun.common.api;

import java.util.List;
import java.util.Map;

public record ApiError(boolean success, String code, String message, Map<String, List<String>> errors) {
    public static ApiError of(String code, String message, Map<String, List<String>> errors) {
        return new ApiError(false, code, message, errors);
    }
}
