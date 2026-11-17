package com.LegacyhealthInsurance.insurance.repository;

import com.LegacyhealthInsurance.insurance.entity.DocumentStatus;
import com.LegacyhealthInsurance.insurance.entity.InsuranceDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InsuranceDocumentRepository extends JpaRepository<InsuranceDocument, Long> {

    boolean existsByDriveFileId(String driveFileId);

    List<InsuranceDocument> findByStatusOrderByFetchedAtAsc(DocumentStatus status);

    List<InsuranceDocument> findByStatusAndExcelWrittenFalse(DocumentStatus status);

    List<InsuranceDocument> findAllByOrderByFetchedAtDesc();
}
