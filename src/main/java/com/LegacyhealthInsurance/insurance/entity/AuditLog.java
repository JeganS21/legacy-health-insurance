package com.LegacyhealthInsurance.insurance.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** One log line, stored in the database so it can be shown in the UI and to Claude. */
@Entity
@Table(name = "audit_logs", indexes = @Index(name = "idx_audit_logs_time", columnList = "timestamp"))
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime timestamp;
    private String level;        // INFO / WARN / ERROR
    private String source;       // SCHEDULER / MCP / API / EXCEL
    private Long documentId;     // nullable

    @Column(length = 2000)
    private String message;

    protected AuditLog() { }

    public AuditLog(String level, String source, Long documentId, String message) {
        this.timestamp = LocalDateTime.now();
        this.level = level;
        this.source = source;
        this.documentId = documentId;
        this.message = message.substring(0, Math.min(message.length(), 2000));
    }

    public Long getId() { return id; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getLevel() { return level; }
    public String getSource() { return source; }
    public Long getDocumentId() { return documentId; }
    public String getMessage() { return message; }
}
