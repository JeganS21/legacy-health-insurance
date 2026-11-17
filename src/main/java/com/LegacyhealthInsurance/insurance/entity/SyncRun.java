package com.LegacyhealthInsurance.insurance.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Summary of one Google Drive sync (every 5 minutes, or when triggered manually). */
@Entity
@Table(name = "sync_runs")
public class SyncRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String triggeredBy;      // SCHEDULER / MCP / API
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private int filesFound;
    private int newFiles;
    private int failedFiles;
    private String status;           // SUCCESS / FAILED / SKIPPED

    @Column(length = 1000)
    private String message;

    protected SyncRun() { }

    public SyncRun(String triggeredBy) {
        this.triggeredBy = triggeredBy;
        this.startedAt = LocalDateTime.now();
    }

    public void finish(String status, int filesFound, int newFiles, int failedFiles, String message) {
        this.status = status;
        this.filesFound = filesFound;
        this.newFiles = newFiles;
        this.failedFiles = failedFiles;
        this.message = message == null ? null : message.substring(0, Math.min(message.length(), 1000));
        this.finishedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getTriggeredBy() { return triggeredBy; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public int getFilesFound() { return filesFound; }
    public int getNewFiles() { return newFiles; }
    public int getFailedFiles() { return failedFiles; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
}
