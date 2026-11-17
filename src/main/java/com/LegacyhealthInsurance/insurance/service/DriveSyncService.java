package com.LegacyhealthInsurance.insurance.service;

import com.LegacyhealthInsurance.insurance.drive.GoogleDriveClient;
import com.LegacyhealthInsurance.insurance.dto.SyncResult;
import com.LegacyhealthInsurance.insurance.entity.DocumentStatus;
import com.LegacyhealthInsurance.insurance.entity.InsuranceDocument;
import com.LegacyhealthInsurance.insurance.entity.SyncRun;
import com.LegacyhealthInsurance.insurance.repository.InsuranceDocumentRepository;
import com.LegacyhealthInsurance.insurance.repository.SyncRunRepository;
import com.google.api.services.drive.model.File;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Every 5 minutes: list the Drive folder, import files we haven't seen,
 * extract their text and mark them PENDING_EXTRACTION for Claude.
 * No AI is used here.
 */
@Service
public class DriveSyncService {

    private final GoogleDriveClient drive;
    private final TextExtractor textExtractor;
    private final InsuranceDocumentRepository documents;
    private final SyncRunRepository syncRuns;
    private final ExtractionService extractionService;
    private final AuditLogService audit;

    public DriveSyncService(GoogleDriveClient drive, TextExtractor textExtractor,
                            InsuranceDocumentRepository documents, SyncRunRepository syncRuns,
                            ExtractionService extractionService, AuditLogService audit) {
        this.drive = drive;
        this.textExtractor = textExtractor;
        this.documents = documents;
        this.syncRuns = syncRuns;
        this.extractionService = extractionService;
        this.audit = audit;
    }

    // fixedDelay: the next run starts 5 minutes after the previous one FINISHES (no overlap)
    @Scheduled(initialDelay = 15_000, fixedDelayString = "${insurance.poll-interval-ms:300000}")
    public void scheduledSync() {
        sync("SCHEDULER");
    }

    public synchronized SyncResult sync(String triggeredBy) {
        SyncRun run = new SyncRun(triggeredBy);

        String problem = drive.configurationProblem();
        if (problem != null) {
            audit.warn(triggeredBy, null, "Sync skipped: " + problem);
            run.finish("SKIPPED", 0, 0, 0, problem);
            syncRuns.save(run);
            return new SyncResult("SKIPPED", 0, 0, 0, problem);
        }

        audit.info(triggeredBy, null, "Sync started");
        int found = 0, imported = 0, failed = 0;
        try {
            List<File> files = drive.listFiles();
            found = files.size();

            for (File file : files) {
                if (documents.existsByDriveFileId(file.getId())) {
                    continue;   // already imported
                }
                if (!textExtractor.isSupported(file.getMimeType())) {
                    audit.info(triggeredBy, null, "Skipped '" + file.getName() + "' (unsupported type " + file.getMimeType() + ")");
                    continue;
                }
                if (importFile(file, triggeredBy)) {
                    imported++;
                } else {
                    failed++;
                }
            }

            int retried = extractionService.retryPendingExcelRows();
            String message = found + " file(s) in folder, " + imported + " new, " + failed + " failed"
                    + (retried > 0 ? ", " + retried + " Excel row(s) retried" : "");
            audit.info(triggeredBy, null, "Sync finished: " + message);
            run.finish(failed > 0 ? "PARTIAL" : "SUCCESS", found, imported, failed, message);
            syncRuns.save(run);
            return new SyncResult(run.getStatus(), found, imported, failed, message);

        } catch (Exception e) {
            audit.error(triggeredBy, null, "Sync failed: " + e.getMessage());
            run.finish("FAILED", found, imported, failed, e.getMessage());
            syncRuns.save(run);
            return new SyncResult("FAILED", found, imported, failed, e.getMessage());
        }
    }

    private boolean importFile(File file, String source) {
        InsuranceDocument doc = new InsuranceDocument(file.getId(), file.getName(), file.getMimeType());
        try {
            byte[] bytes = GoogleDriveClient.GOOGLE_DOC.equals(file.getMimeType())
                    ? drive.exportGoogleDocAsText(file.getId())
                    : drive.download(file.getId());
            String text = textExtractor.extract(bytes, file.getMimeType());

            if (text.isBlank()) {
                doc.setStatus(DocumentStatus.FAILED);
                doc.setErrorMessage("No text found - is it a scanned image PDF? (OCR is not supported)");
            } else {
                doc.setRawText(text);
                doc.setStatus(DocumentStatus.PENDING_EXTRACTION);
            }
        } catch (Exception e) {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(e.getMessage());
        }

        documents.save(doc);
        if (doc.getStatus() == DocumentStatus.FAILED) {
            audit.error(source, doc.getId(), "Could not read '" + file.getName() + "': " + doc.getErrorMessage());
            return false;
        }
        audit.info(source, doc.getId(), "Imported '" + file.getName() + "' (" + doc.getRawText().length()
                + " characters) - waiting for Claude to extract fields");
        return true;
    }

    public List<SyncRun> latestRuns(int limit) {
        return syncRuns.findAllByOrderByStartedAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
    }
}
