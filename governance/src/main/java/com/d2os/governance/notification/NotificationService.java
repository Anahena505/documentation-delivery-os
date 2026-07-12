package com.d2os.governance.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app-only notification store (Phase 6 US4, T034, research R4, FR-010). v1 has no email/webhook
 * delivery channel (application.yml's own T003 comment states this explicitly) — a persisted, role-
 * addressed {@code in_app_notification} row IS the delivery. {@code source_module}/{@code type} are
 * deliberately not CHECK-constrained (V20's own comment) so a later phase (e.g. projection cycle
 * alerts) can reuse this exact store without a schema change.
 */
@Service
public class NotificationService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public NotificationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public UUID notifyRole(
      UUID workspaceId,
      String recipientRole,
      String sourceModule,
      String type,
      Map<String, Object> subjectRef,
      String message) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO in_app_notification (id, workspace_id, recipient_role, source_module, type, subject_ref, message) "
            + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)",
        id,
        workspaceId,
        recipientRole,
        sourceModule,
        type,
        toJson(subjectRef),
        message);
    return id;
  }

  /**
   * {@code GET /notifications} (T034, contracts/api.yaml) — the caller's roles, optionally unread
   * only.
   */
  public List<Map<String, Object>> forRoles(
      UUID workspaceId, List<String> roles, boolean unreadOnly) {
    if (roles.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", roles.stream().map(r -> "?").toList());
    String sql =
        "SELECT id, recipient_role, source_module, type, subject_ref, message, created_at, read_at "
            + "FROM in_app_notification WHERE workspace_id = ? AND recipient_role IN ("
            + placeholders
            + ")"
            + (unreadOnly ? " AND read_at IS NULL" : "")
            + " ORDER BY created_at DESC";
    Object[] args = new Object[1 + roles.size()];
    args[0] = workspaceId;
    for (int i = 0; i < roles.size(); i++) args[i + 1] = roles.get(i);
    return jdbcTemplate.queryForList(sql, args);
  }

  @Transactional
  public void markRead(UUID notificationId) {
    jdbcTemplate.update(
        "UPDATE in_app_notification SET read_at = now() WHERE id = ? AND read_at IS NULL",
        notificationId);
  }

  private String toJson(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unserializable notification subjectRef", e);
    }
  }
}
