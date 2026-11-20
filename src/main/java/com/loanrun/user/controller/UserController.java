package com.loanrun.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.user.dto.UserResponse;
import com.loanrun.user.service.UserService;
import com.loanrun.user.service.UserWithdrawalService;
import com.loanrun.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final CurrentUserProvider currentUser;
    private final UserService service;
    private final UserWithdrawalService withdrawal;
    public UserController(CurrentUserProvider currentUser, UserService service, UserWithdrawalService withdrawal) {
        this.currentUser = currentUser; this.service = service; this.withdrawal = withdrawal;
    }

    @GetMapping("/me/")
    public ApiResponse<UserResponse> getMe() {
        return ApiResponse.success("USER_DETAIL_SUCCESS", "프로필을 조회했습니다.", service.get(currentUser.currentUserId()));
    }

    @PatchMapping("/me/")
    public ApiResponse<UserResponse> patchMe(@RequestBody JsonNode body) {
        return ApiResponse.success("USER_UPDATE_SUCCESS", "프로필을 수정했습니다.", service.patch(currentUser.currentUserId(), body));
    }

    @DeleteMapping("/me/")
    public ApiResponse<Map<String, Boolean>> deleteMe(@RequestBody(required=false) JsonNode body, HttpServletRequest request) {
        if (body == null || !body.isObject() || !body.path("confirm").isBoolean() || !body.path("confirm").booleanValue()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIRMATION_REQUIRED", "회원 탈퇴를 확인해 주세요.",
                Map.of("confirm", List.of("true로 설정해야 합니다.")));
        }
        withdrawal.withdraw(currentUser.currentUserId());
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        return ApiResponse.success("USER_DELETE_SUCCESS", "회원 탈퇴를 완료했습니다.", Map.of("deleted", true));
    }
}
