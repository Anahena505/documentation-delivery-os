package com.d2os.governance.api;

import com.d2os.governance.notification.NotificationService;
import com.d2os.tenancy.WorkspaceContext;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /notifications} (T034, contracts/api.yaml, FR-010). {@code X-Roles} (comma-separated)
 * identifies the caller's roles as a pragmatic input — same posture as {@code GateController}'s
 * {@code X-Actor} header, pending a real auth-principal/role model.
 */
@RestController
public class NotificationController {

  private final NotificationService notificationService;

  public NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @GetMapping("/api/v1/notifications")
  public List<Map<String, Object>> list(
      @RequestHeader(value = "X-Roles", defaultValue = "reviewer") String roles,
      @RequestParam(defaultValue = "false") boolean unreadOnly) {
    UUID workspaceId = WorkspaceContext.require();
    return notificationService.forRoles(workspaceId, Arrays.asList(roles.split(",")), unreadOnly);
  }

  @PostMapping("/api/v1/notifications/{id}/read")
  public void markRead(@PathVariable UUID id) {
    notificationService.markRead(id);
  }
}
