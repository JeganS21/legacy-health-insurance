package com.LegacyhealthInsurance.insurance.controller;

import com.LegacyhealthInsurance.insurance.config.WorkflowDefinition;
import com.LegacyhealthInsurance.insurance.config.WorkflowLoader;
import com.LegacyhealthInsurance.insurance.dto.DocumentInfo;
import com.LegacyhealthInsurance.insurance.dto.SyncResult;
import com.LegacyhealthInsurance.insurance.entity.AuditLog;
import com.LegacyhealthInsurance.insurance.entity.SyncRun;
import com.LegacyhealthInsurance.insurance.service.AuditLogService;
import com.LegacyhealthInsurance.insurance.service.DriveSyncService;
import com.LegacyhealthInsurance.insurance.service.ExtractionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** REST API used by the dashboard page (http://localhost:8081/). */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final ExtractionService extractionService;
    private final DriveSyncService driveSyncService;
    private final AuditLogService audit;
    private final WorkflowLoader workflowLoader;

    public DashboardController(ExtractionService extractionService, DriveSyncService driveSyncService,
                               AuditLogService audit, WorkflowLoader workflowLoader) {
        this.extractionService = extractionService;
        this.driveSyncService = driveSyncService;
        this.audit = audit;
        this.workflowLoader = workflowLoader;
    }

    @GetMapping("/documents")
    public List<DocumentInfo> documents() {
        return extractionService.allDocuments();
    }

    @GetMapping("/logs")
    public List<AuditLog> logs(@RequestParam(defaultValue = "100") int limit) {
        return audit.latest(limit);
    }

    @GetMapping("/sync-runs")
    public List<SyncRun> syncRuns(@RequestParam(defaultValue = "20") int limit) {
        return driveSyncService.latestRuns(limit);
    }

    @PostMapping("/sync")
    public SyncResult syncNow() {
        return driveSyncService.sync("API");
    }

    @GetMapping("/workflow")
    public WorkflowDefinition workflow() {
        return workflowLoader.load();
    }
}
