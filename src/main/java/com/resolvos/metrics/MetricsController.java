package com.resolvos.metrics;

import com.resolvos.metrics.dto.DashboardMetricsResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/summary")
    public ResponseEntity<DashboardMetricsResponse> getSummary(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String companyId) {
        String effectiveTenant = tenantId != null && !tenantId.isBlank() ? tenantId : companyId;
        UUID resolvedTenantId = metricsService.resolveTenantId(effectiveTenant);
        DashboardMetricsResponse response = metricsService.getMetrics(resolvedTenantId);
        return ResponseEntity.ok(response);
    }
}
