package com.resolvos.audit;

import com.resolvos.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditController {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditController(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'REVIEWER')")
    public ResponseEntity<Object> getAuditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) String caseId,
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit) {

        StringBuilder whereSql = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        String ctxTenant = TenantContext.getTenantId();
        if (ctxTenant != null && !ctxTenant.isBlank()) {
            whereSql.append(" AND a.tenant_id = ? ");
            params.add(UUID.fromString(ctxTenant));
        } else if (tenant != null && !tenant.isBlank() && !"ALL".equalsIgnoreCase(tenant)) {
            try {
                UUID tenantUuid = UUID.fromString(tenant);
                whereSql.append(" AND a.tenant_id = ? ");
                params.add(tenantUuid);
            } catch (IllegalArgumentException e) {
                whereSql.append(" AND (t.name ILIKE ? OR t.slug ILIKE ?) ");
                params.add("%" + tenant + "%");
                params.add("%" + tenant + "%");
            }
        }

        if (action != null && !action.isBlank() && !"ALL".equalsIgnoreCase(action)) {
            whereSql.append(" AND UPPER(a.action) = UPPER(?) ");
            params.add(action.trim());
        }

        if (actorType != null && !actorType.isBlank() && !"ALL".equalsIgnoreCase(actorType)) {
            whereSql.append(" AND UPPER(a.actor_type) = UPPER(?) ");
            params.add(actorType.trim());
        }

        if (caseId != null && !caseId.isBlank()) {
            whereSql.append(" AND a.case_id::text ILIKE ? ");
            params.add("%" + caseId.trim() + "%");
        }

        if (from != null && !from.isBlank()) {
            Instant fromInstant = parseDateTime(from, false);
            if (fromInstant != null) {
                whereSql.append(" AND a.ts >= ? ");
                params.add(java.sql.Timestamp.from(fromInstant));
            }
        }
        if (to != null && !to.isBlank()) {
            Instant toInstant = parseDateTime(to, true);
            if (toInstant != null) {
                whereSql.append(" AND a.ts <= ? ");
                params.add(java.sql.Timestamp.from(toInstant));
            }
        }

        boolean isPaged = page != null || size != null || limit != null;

        if (!isPaged) {
            String querySql = "SELECT a.id, a.ts, a.tenant_id, t.name as tenant_name, t.slug as tenant_slug, " +
                    "a.case_id, a.actor, a.actor_type, a.action, a.old_state, a.new_state, a.trace_id, a.metadata " +
                    "FROM audit_logs a " +
                    "LEFT JOIN tenants t ON t.id = a.tenant_id " +
                    whereSql +
                    " ORDER BY a.ts DESC LIMIT 500";
            List<AuditRecordResponse> list = jdbcTemplate.query(querySql, this::mapRow, params.toArray());
            return ResponseEntity.ok(list);
        }

        String countSql = "SELECT COUNT(*) FROM audit_logs a " +
                "LEFT JOIN tenants t ON t.id = a.tenant_id " +
                whereSql;
        Long totalCountLong = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        long totalElements = totalCountLong != null ? totalCountLong : 0L;

        int p = page != null && page > 0 ? page : 1;
        int s = limit != null && limit > 0 ? limit : (size != null && size > 0 ? size : 20);
        int offset = (p - 1) * s;

        String querySql = "SELECT a.id, a.ts, a.tenant_id, t.name as tenant_name, t.slug as tenant_slug, " +
                "a.case_id, a.actor, a.actor_type, a.action, a.old_state, a.new_state, a.trace_id, a.metadata " +
                "FROM audit_logs a " +
                "LEFT JOIN tenants t ON t.id = a.tenant_id " +
                whereSql +
                " ORDER BY a.ts DESC LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(s);
        queryParams.add(offset);

        List<AuditRecordResponse> items = jdbcTemplate.query(querySql, this::mapRow, queryParams.toArray());
        int totalPages = s > 0 ? (int) Math.ceil((double) totalElements / s) : 1;
        if (totalPages == 0 && totalElements == 0) totalPages = 1;

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("totalElements", totalElements);
        resp.put("page", p);
        resp.put("size", s);
        resp.put("totalPages", totalPages);
        return ResponseEntity.ok(resp);
    }

    private AuditRecordResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        java.sql.Timestamp ts = rs.getTimestamp("ts");
        String tsStr = ts != null ? ts.toInstant().toString() : Instant.now().toString();

        String tenantName = rs.getString("tenant_name");
        if (tenantName == null || tenantName.isBlank()) {
            tenantName = rs.getString("tenant_slug");
        }
        if (tenantName == null || tenantName.isBlank()) {
            UUID tenantId = rs.getObject("tenant_id", UUID.class);
            tenantName = tenantId != null ? tenantId.toString() : "System";
        }

        UUID caseId = rs.getObject("case_id", UUID.class);
        String actor = rs.getString("actor");
        String actorType = normalizeActorType(rs.getString("actor_type"));
        String action = rs.getString("action");
        String oldState = rs.getString("old_state");
        String newState = rs.getString("new_state");
        String traceId = rs.getString("trace_id");
        String metaJson = rs.getString("metadata");

        Object metadataObj = null;
        if (metaJson != null && !metaJson.isBlank()) {
            try {
                metadataObj = objectMapper.readValue(metaJson, Object.class);
            } catch (Exception e) {
                metadataObj = metaJson;
            }
        }

        return new AuditRecordResponse(
                id != null ? id.toString() : "",
                tsStr,
                tenantName,
                caseId != null ? caseId.toString() : "",
                actor != null ? actor : "",
                actorType,
                action != null ? action : "",
                oldState != null ? oldState : "NONE",
                newState != null ? newState : "",
                traceId != null ? traceId : "",
                metadataObj != null ? metadataObj : Map.of()
        );
    }

    private String normalizeActorType(String raw) {
        if (raw == null) return "System";
        return switch (raw.toUpperCase()) {
            case "AI" -> "AI";
            case "HUMAN" -> "Human";
            case "CUSTOMER" -> "Customer";
            case "SYSTEM" -> "System";
            default -> raw;
        };
    }

    private Instant parseDateTime(String text, boolean endOfDay) {
        if (text == null || text.isBlank()) return null;
        try {
            if (text.length() == 10 && text.charAt(4) == '-' && text.charAt(7) == '-') {
                java.time.LocalDate ld = java.time.LocalDate.parse(text);
                return endOfDay
                        ? ld.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().minusNanos(1000)
                        : ld.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            }
            return Instant.parse(text);
        } catch (Exception e) {
            return null;
        }
    }
}
