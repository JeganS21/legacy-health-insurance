package com.LegacyhealthInsurance.insurance.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Turns file bytes into plain text. */
@Component
public class TextExtractor {

    public boolean isSupported(String mimeType) {
        return "application/pdf".equals(mimeType)
                || "text/plain".equals(mimeType)
                || com.LegacyhealthInsurance.insurance.drive.GoogleDriveClient.GOOGLE_DOC.equals(mimeType);
    }

    public String extract(byte[] bytes, String mimeType) throws IOException {
        if ("application/pdf".equals(mimeType)) {
            try (PDDocument pdf = Loader.loadPDF(bytes)) {
                return new PDFTextStripper().getText(pdf).trim();
            }
        }
        // text/plain and exported Google Docs
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }
}
