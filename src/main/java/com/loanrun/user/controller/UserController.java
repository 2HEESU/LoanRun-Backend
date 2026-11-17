package com.loanrun.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.user.dto.UserResponse;
import com.loanrun.user.service.UserService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final CurrentUserProvider currentUser;
    private final UserService service;
    public UserController(CurrentUserProvider currentUser, UserService service) { this.currentUser = currentUser; this.service = service; }

    @GetMapping("/me/")
    public ApiResponse<UserResponse> getMe() {
        return ApiResponse.success("USER_DETAIL_SUCCESS", "프로필을 조회했습니다.", service.get(currentUser.currentUserId()));
    }

    @PatchMapping("/me/")
    public ApiResponse<UserResponse> patchMe(@RequestBody JsonNode body) {
        return ApiResponse.success("USER_UPDATE_SUCCESS", "프로필을 수정했습니다.", service.patch(currentUser.currentUserId(), body));
    }
}
