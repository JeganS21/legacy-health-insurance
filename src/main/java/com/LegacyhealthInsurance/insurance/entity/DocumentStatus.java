package com.LegacyhealthInsurance.insurance.entity;

public enum DocumentStatus {
    PENDING_EXTRACTION,   // text extracted from the file, waiting for Claude
    EXTRACTED,            // Claude's fields saved to DB (and Excel)
    FAILED                // could not read the file (e.g. scanned image PDF)
}
