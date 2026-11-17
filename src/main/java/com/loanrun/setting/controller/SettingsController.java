package com.loanrun.setting.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.setting.dto.*;
import com.loanrun.setting.service.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final CurrentUserProvider currentUser;
    private final SubscriptionService subscriptions;
    private final NotificationService notifications;
    public SettingsController(CurrentUserProvider currentUser, SubscriptionService subscriptions, NotificationService notifications) {
        this.currentUser = currentUser; this.subscriptions = subscriptions; this.notifications = notifications;
    }

    @GetMapping("/subscription/")
    public ApiResponse<SubscriptionResponse> subscription() {
        return ApiResponse.success("SUBSCRIPTION_DETAIL_SUCCESS", "요금제 정보 정보를 조회했습니다.", subscriptions.get(currentUser.currentUserId()));
    }

    @PatchMapping("/subscription/")
    public ApiResponse<SubscriptionResponse> updateSubscription(@RequestBody JsonNode body) {
        return ApiResponse.success("SUBSCRIPTION_UPDATE_SUCCESS", "요금제 정보를 변경했습니다.", subscriptions.patch(currentUser.currentUserId(), body));
    }

    @GetMapping("/notifications/")
    public ApiResponse<NotificationResponse> notifications() {
        return ApiResponse.success("NOTIFICATION_SETTINGS_SUCCESS", "알림 설정 정보를 조회했습니다.", notifications.get(currentUser.currentUserId()));
    }

    @PatchMapping("/notifications/")
    public ApiResponse<NotificationResponse> updateNotifications(@RequestBody JsonNode body) {
        return ApiResponse.success("NOTIFICATION_SETTINGS_UPDATE_SUCCESS", "알림 설정을 저장했습니다.", notifications.patch(currentUser.currentUserId(), body));
    }
}
