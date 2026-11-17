package com.LegacyhealthInsurance.insurance.controller;

import com.LegacyhealthInsurance.insurance.config.WorkflowDefinition;
import com.LegacyhealthInsurance.insurance.config.WorkflowLoader;
import com.LegacyhealthInsurance.insurance.dto.DocumentInfo;
import com.LegacyhealthInsurance.insurance.dto.ExtractResult;
import com.LegacyhealthInsurance.insurance.dto.SyncResult;
import com.LegacyhealthInsurance.insurance.entity.AuditLog;
import com.LegacyhealthInsurance.insurance.entity.SyncRun;
import com.LegacyhealthInsurance.insurance.service.AuditLogService;
import com.LegacyhealthInsurance.insurance.service.ClaudeExtractionService;
import com.LegacyhealthInsurance.insurance.service.DriveSyncService;
import com.LegacyhealthInsurance.insurance.service.ExtractionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** REST API used by the dashboard page (http://localhost:8081/). */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final ExtractionService extractionService;
    private final DriveSyncService driveSyncService;
    private final AuditLogService audit;
    private final WorkflowLoader workflowLoader;
    private final ClaudeExtractionService claudeExtraction;

    public DashboardController(ExtractionService extractionService, DriveSyncService driveSyncService,
                               AuditLogService audit, WorkflowLoader workflowLoader,
                               ClaudeExtractionService claudeExtraction) {
        this.extractionService = extractionService;
        this.driveSyncService = driveSyncService;
        this.audit = audit;
        this.workflowLoader = workflowLoader;
        this.claudeExtraction = claudeExtraction;
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

    /** Lets the page show or disable the Extract buttons. */
    @GetMapping("/extract/status")
    public Map<String, Object> extractStatus() {
        boolean configured = claudeExtraction.isConfigured();
        String problem = claudeExtraction.problem();
        return Map.of("configured", configured, "model", claudeExtraction.model(),
                "problem", problem == null ? "" : problem);
    }

    /** "Extract" button on one document row. */
    @PostMapping("/documents/{id}/extract")
    public ExtractResult extract(@PathVariable Long id) {
        return claudeExtraction.extract(id);
    }

    /** "Extract all pending" button in the header. */
    @PostMapping("/extract-pending")
    public List<ExtractResult> extractAllPending() {
        return claudeExtraction.extractAllPending();
    }
}
