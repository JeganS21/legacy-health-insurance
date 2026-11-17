package com.LegacyhealthInsurance.insurance.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "insurance_documents")
public class InsuranceDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String driveFileId;          // used to skip files already imported

    private String fileName;
    private String mimeType;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String rawText;              // text pulled out of the PDF / doc

    @Enumerated(EnumType.STRING)
    private DocumentStatus status;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String extractedJson;        // fields Claude extracted, as JSON

    private boolean excelWritten;        // false = retry Excel write on next sync

    @Column(length = 1000)
    private String errorMessage;

    private LocalDateTime fetchedAt;
    private LocalDateTime extractedAt;

    protected InsuranceDocument() { }

    public InsuranceDocument(String driveFileId, String fileName, String mimeType) {
        this.driveFileId = driveFileId;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.fetchedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getDriveFileId() { return driveFileId; }
    public String getFileName() { return fileName; }
    public String getMimeType() { return mimeType; }
    public String getRawText() { return rawText; }
    public DocumentStatus getStatus() { return status; }
    public String getExtractedJson() { return extractedJson; }
    public boolean isExcelWritten() { return excelWritten; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getFetchedAt() { return fetchedAt; }
    public LocalDateTime getExtractedAt() { return extractedAt; }

    public void setRawText(String rawText) { this.rawText = rawText; }
    public void setStatus(DocumentStatus status) { this.status = status; }
    public void setExtractedJson(String extractedJson) { this.extractedJson = extractedJson; }
    public void setExcelWritten(boolean excelWritten) { this.excelWritten = excelWritten; }
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage == null ? null
                : errorMessage.substring(0, Math.min(errorMessage.length(), 1000));
    }
    public void setExtractedAt(LocalDateTime extractedAt) { this.extractedAt = extractedAt; }
}
