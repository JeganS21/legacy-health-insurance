package com.LegacyhealthInsurance.insurance.service;

import com.LegacyhealthInsurance.insurance.config.WorkflowDefinition;
import com.LegacyhealthInsurance.insurance.config.WorkflowLoader;
import com.LegacyhealthInsurance.insurance.dto.DocumentInfo;
import com.LegacyhealthInsurance.insurance.entity.DocumentStatus;
import com.LegacyhealthInsurance.insurance.entity.InsuranceDocument;
import com.LegacyhealthInsurance.insurance.repository.InsuranceDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The "brain": receives the fields Claude extracted, validates them,
 * stores them in MySQL and appends a row to the Excel sheet.
 */
@Service
public class ExtractionService {

    private final InsuranceDocumentRepository documents;
    private final WorkflowLoader workflowLoader;
    private final FieldValidator validator;
    private final ExcelWriter excelWriter;
    private final AuditLogService audit;
    private final JsonMapper jsonMapper;

    public ExtractionService(InsuranceDocumentRepository documents, WorkflowLoader workflowLoader,
                             FieldValidator validator, ExcelWriter excelWriter,
                             AuditLogService audit, JsonMapper jsonMapper) {
        this.documents = documents;
        this.workflowLoader = workflowLoader;
        this.validator = validator;
        this.excelWriter = excelWriter;
        this.audit = audit;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(readOnly = true)
    public List<DocumentInfo> pendingDocuments() {
        return documents.findByStatusOrderByFetchedAtAsc(DocumentStatus.PENDING_EXTRACTION)
                .stream().map(DocumentInfo::from).toList();
    }

    @Transactional(readOnly = true)
    public List<DocumentInfo> allDocuments() {
        return documents.findAllByOrderByFetchedAtDesc().stream().map(DocumentInfo::from).toList();
    }

    @Transactional(readOnly = true)
    public String documentText(Long documentId) {
        return find(documentId).getRawText();
    }

    /** Called by Claude (through MCP) with the fields it extracted. */
    @Transactional
    public String saveExtractedFields(Long documentId, Map<String, Object> fields, String source) {
        InsuranceDocument doc = find(documentId);
        if (doc.getStatus() != DocumentStatus.PENDING_EXTRACTION) {
            return "Document " + documentId + " is not pending (status " + doc.getStatus() + "). Nothing saved.";
        }

        WorkflowDefinition workflow = workflowLoader.load();
        FieldValidator.Result result = validator.validate(workflow, fields);
        if (!result.isValid()) {
            audit.warn(source, documentId, "Rejected extracted fields: " + result.errors());
            return "REJECTED - fix these problems and call saveExtractedFields again: " + result.errors();
        }

        doc.setExtractedJson(jsonMapper.writeValueAsString(result.cleanValues()));
        doc.setStatus(DocumentStatus.EXTRACTED);
        doc.setExtractedAt(LocalDateTime.now());
        doc.setErrorMessage(null);
        audit.info(source, documentId, "Fields saved to database for " + doc.getFileName());

        try {
            writeExcelRow(workflow, doc, result.cleanValues());
            doc.setExcelWritten(true);
            audit.info("EXCEL", documentId, "Row added to " + excelWriter.path());
            return "SAVED - document " + documentId + " (" + doc.getFileName() + ") stored in the database and added to Excel.";
        } catch (IOException e) {
            doc.setExcelWritten(false);
            audit.error("EXCEL", documentId, "Excel write failed: " + e.getMessage()
                    + " (if the file is open in Excel, close it - the server retries on the next sync)");
            return "SAVED to database, but the Excel write failed: " + e.getMessage()
                    + ". It will be retried automatically on the next sync.";
        }
    }

    /** Retries Excel rows that failed earlier (e.g. the file was open in Excel). */
    @Transactional
    public int retryPendingExcelRows() {
        List<InsuranceDocument> waiting = documents.findByStatusAndExcelWrittenFalse(DocumentStatus.EXTRACTED);
        if (waiting.isEmpty()) {
            return 0;
        }
        WorkflowDefinition workflow = workflowLoader.load();
        int written = 0;
        for (InsuranceDocument doc : waiting) {
            try {
                Map<String, Object> raw = jsonMapper.readValue(doc.getExtractedJson(), new TypeReference<>() { });
                FieldValidator.Result result = validator.validate(workflow, raw);
                writeExcelRow(workflow, doc, result.cleanValues());
                doc.setExcelWritten(true);
                written++;
                audit.info("EXCEL", doc.getId(), "Retry succeeded - row added to Excel");
            } catch (IOException e) {
                audit.error("EXCEL", doc.getId(), "Retry failed: " + e.getMessage());
                break;   // file is probably still locked; try again next sync
            }
        }
        return written;
    }

    private void writeExcelRow(WorkflowDefinition workflow, InsuranceDocument doc,
                               Map<String, Object> values) throws IOException {
        List<String> header = new ArrayList<>(List.of("Document ID", "File Name", "Extracted At"));
        header.addAll(workflow.fieldNames());

        List<Object> row = new ArrayList<>();
        row.add(doc.getId());
        row.add(doc.getFileName());
        row.add(doc.getExtractedAt().toString());
        for (String name : workflow.fieldNames()) {
            row.add(values.get(name));
        }
        excelWriter.appendRow(workflow.sheetName(), header, row);
    }

    private InsuranceDocument find(Long documentId) {
        return documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("No document with ID " + documentId));
    }
}
