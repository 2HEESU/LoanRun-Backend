package com.loanrun.common.error;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, List<String>> errors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, List<String>> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = Map.copyOf(errors);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, List<String>> errors() { return errors; }

    public static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "입력값을 확인해 주세요.",
            Map.of(field, List.of(message)));
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "일시적으로 서비스를 이용할 수 없습니다.");
    }
}
