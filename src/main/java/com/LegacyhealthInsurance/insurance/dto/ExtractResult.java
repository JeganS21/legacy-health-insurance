package com.LegacyhealthInsurance.insurance.dto;

/** Outcome of one "Extract" button press for one document. */
public record ExtractResult(Long documentId, boolean saved, String message) {
}
