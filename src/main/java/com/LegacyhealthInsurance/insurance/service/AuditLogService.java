package com.LegacyhealthInsurance.insurance.service;

import com.LegacyhealthInsurance.insurance.entity.AuditLog;
import com.LegacyhealthInsurance.insurance.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Writes every log line twice: to the normal log file (logs/insurance.log)
 * and to the audit_logs table, so the UI and Claude can read them.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void info(String source, Long documentId, String message) { write("INFO", source, documentId, message); }
    public void warn(String source, Long documentId, String message) { write("WARN", source, documentId, message); }
    public void error(String source, Long documentId, String message) { write("ERROR", source, documentId, message); }

    public List<AuditLog> latest(int limit) {
        return repository.findAllByOrderByTimestampDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    private void write(String level, String source, Long documentId, String message) {
        String line = "[" + source + "]" + (documentId != null ? " [doc " + documentId + "] " : " ") + message;
        switch (level) {
            case "ERROR" -> log.error(line);
            case "WARN" -> log.warn(line);
            default -> log.info(line);
        }
        repository.save(new AuditLog(level, source, documentId, message));
    }
}
