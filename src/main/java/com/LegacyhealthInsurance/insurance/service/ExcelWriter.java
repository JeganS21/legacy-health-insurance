package com.LegacyhealthInsurance.insurance.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Appends one row per document to the Excel file.
 * Creates the file (with a header row from workflow.yaml) the first time.
 */
@Component
public class ExcelWriter {

    private final Path excelPath;

    public ExcelWriter(@Value("${insurance.excel.path}") String excelPath) {
        this.excelPath = Path.of(excelPath);
    }

    public Path path() {
        return excelPath.toAbsolutePath();
    }

    /** synchronized: two writes at the same time would corrupt the file. */
    public synchronized void appendRow(String sheetName, List<String> header, List<Object> values) throws IOException {
        Workbook workbook;
        if (Files.exists(excelPath)) {
            try (InputStream in = Files.newInputStream(excelPath)) {
                workbook = new XSSFWorkbook(in);
            }
        } else {
            if (excelPath.getParent() != null) {
                Files.createDirectories(excelPath.getParent());
            }
            workbook = new XSSFWorkbook();
        }

        try (workbook) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                sheet = workbook.createSheet(sheetName);
                writeHeader(workbook, sheet, header);
            } else {
                checkHeader(sheet, header);
            }

            Row row = sheet.createRow(sheet.getLastRowNum() + 1);
            for (int i = 0; i < values.size(); i++) {
                Cell cell = row.createCell(i);
                Object v = values.get(i);
                if (v == null) {
                    cell.setBlank();
                } else if (v instanceof BigDecimal n) {
                    cell.setCellValue(n.doubleValue());
                } else if (v instanceof Number n) {
                    cell.setCellValue(n.doubleValue());
                } else if (v instanceof Boolean b) {
                    cell.setCellValue(b);
                } else {
                    cell.setCellValue(v.toString());
                }
            }

            // Write to a temp file first, then replace - so a crash never leaves a half-written file
            Path temp = excelPath.resolveSibling(excelPath.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                workbook.write(out);
            }
            Files.move(temp, excelPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeHeader(Workbook workbook, Sheet sheet, List<String> header) {
        Font bold = workbook.createFont();
        bold.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(bold);

        Row row = sheet.createRow(0);
        for (int i = 0; i < header.size(); i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(header.get(i));
            cell.setCellStyle(style);
            sheet.setColumnWidth(i, 22 * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    private void checkHeader(Sheet sheet, List<String> header) throws IOException {
        Row first = sheet.getRow(0);
        for (int i = 0; i < header.size(); i++) {
            Cell cell = first == null ? null : first.getCell(i);
            String existing = cell == null ? "" : cell.getStringCellValue();
            if (!header.get(i).equals(existing)) {
                throw new IOException("Excel columns don't match workflow.yaml (column " + (i + 1) + ": '"
                        + existing + "' vs '" + header.get(i) + "'). Rename or delete " + path()
                        + " so a new file is created.");
            }
        }
    }
}
