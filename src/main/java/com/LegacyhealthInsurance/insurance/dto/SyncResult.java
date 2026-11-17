package com.LegacyhealthInsurance.insurance.dto;

public record SyncResult(String status, int filesFound, int newFiles, int failedFiles, String message) {
}
