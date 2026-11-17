package com.LegacyhealthInsurance.insurance.mcp;

import com.LegacyhealthInsurance.insurance.config.WorkflowDefinition;
import com.LegacyhealthInsurance.insurance.config.WorkflowLoader;
import com.LegacyhealthInsurance.insurance.dto.DocumentInfo;
import com.LegacyhealthInsurance.insurance.dto.SyncResult;
import com.LegacyhealthInsurance.insurance.entity.AuditLog;
import com.LegacyhealthInsurance.insurance.service.AuditLogService;
import com.LegacyhealthInsurance.insurance.service.DriveSyncService;
import com.LegacyhealthInsurance.insurance.service.ExtractionService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * MCP layer - the tools Claude Desktop uses to run the extraction workflow:
 *   1. getWorkflowInstructions  -> learn which fields to extract
 *   2. listPendingDocuments     -> find work to do
 *   3. getDocumentText          -> read a document
 *   4. saveExtractedFields      -> send back the answer (server validates + writes Excel)
 *   5. getIngestionLogs / syncDriveNow -> monitor and control the pipeline
 */
@Component
public class InsuranceTools {

    private static final int MAX_TEXT = 150_000;

    private final WorkflowLoader workflowLoader;
    private final ExtractionService extractionService;
    private final DriveSyncService driveSyncService;
    private final AuditLogService audit;
    private final JsonMapper jsonMapper;

    public InsuranceTools(WorkflowLoader workflowLoader, ExtractionService extractionService,
                          DriveSyncService driveSyncService, AuditLogService audit, JsonMapper jsonMapper) {
        this.workflowLoader = workflowLoader;
        this.extractionService = extractionService;
        this.driveSyncService = driveSyncService;
        this.audit = audit;
        this.jsonMapper = jsonMapper;
    }

    @McpTool(name = "getWorkflowInstructions",
             description = "ALWAYS call this first. Returns the extraction instructions and the exact list of fields "
                     + "(name, type, required, description) to extract from each insurance document.")
    public WorkflowDefinition getWorkflowInstructions() {
        return workflowLoader.load();
    }

    @McpTool(name = "listPendingDocuments",
             description = "List insurance documents imported from Google Drive that are waiting for field extraction.")
    public List<DocumentInfo> listPendingDocuments() {
        return extractionService.pendingDocuments();
    }

    @McpTool(name = "getDocumentText",
             description = "Get the full text of one insurance document so you can read it and extract the fields.")
    public String getDocumentText(
            @McpToolParam(description = "documentId from listPendingDocuments") Long documentId) {
        String text = extractionService.documentText(documentId);
        if (text == null) {
            return "This document has no text.";
        }
        return text.length() <= MAX_TEXT ? text
                : text.substring(0, MAX_TEXT) + "\n\n[Text truncated at " + MAX_TEXT + " characters]";
    }

    @McpTool(name = "saveExtractedFields",
             description = "Save the fields you extracted from one document. The server validates them against the "
                     + "workflow, stores them in the database and adds a row to the Excel sheet. If the response "
                     + "starts with REJECTED, fix the listed problems and call this tool again.")
    public String saveExtractedFields(
            @McpToolParam(description = "documentId of the document you extracted") Long documentId,
            @McpToolParam(description = "A JSON object whose keys are the workflow field names, e.g. "
                    + "{\"policyNumber\":\"P-123\",\"insurer\":\"Star Health\",\"expiryDate\":\"2027-03-31\"}. "
                    + "Use null for values not in the document.") String fieldsJson) {
        Map<String, Object> fields;
        try {
            fields = jsonMapper.readValue(fieldsJson, new TypeReference<>() { });
        } catch (JacksonException e) {
            return "REJECTED - fieldsJson is not a valid JSON object: " + e.getOriginalMessage();
        }
        return extractionService.saveExtractedFields(documentId, fields, "MCP");
    }

    @McpTool(name = "getIngestionLogs",
             description = "Get the most recent pipeline log lines (Drive syncs, imports, extractions, Excel writes, errors).")
    public List<AuditLog> getIngestionLogs(
            @McpToolParam(description = "How many log lines to return, e.g. 30") Integer limit) {
        return audit.latest(limit == null ? 30 : limit);
    }

    @McpTool(name = "syncDriveNow",
             description = "Check the Google Drive folder for new insurance files right now instead of waiting for "
                     + "the 5-minute schedule.")
    public SyncResult syncDriveNow() {
        return driveSyncService.sync("MCP");
    }
}
