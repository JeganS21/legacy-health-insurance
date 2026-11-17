package com.LegacyhealthInsurance.insurance.dto;

import com.LegacyhealthInsurance.insurance.entity.DocumentStatus;
import com.LegacyhealthInsurance.insurance.entity.InsuranceDocument;

import java.time.LocalDateTime;

/** Document summary without the (large) raw text. */
public record DocumentInfo(Long documentId, String fileName, DocumentStatus status, int textLength,
                           boolean excelWritten, String errorMessage,
                           LocalDateTime fetchedAt, LocalDateTime extractedAt, String extractedJson) {

    public static DocumentInfo from(InsuranceDocument d) {
        return new DocumentInfo(d.getId(), d.getFileName(), d.getStatus(),
                d.getRawText() == null ? 0 : d.getRawText().length(),
                d.isExcelWritten(), d.getErrorMessage(), d.getFetchedAt(), d.getExtractedAt(),
                d.getExtractedJson());
    }
}
