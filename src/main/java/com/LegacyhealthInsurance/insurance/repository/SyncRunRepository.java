package com.LegacyhealthInsurance.insurance.repository;

import com.LegacyhealthInsurance.insurance.entity.SyncRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SyncRunRepository extends JpaRepository<SyncRun, Long> {

    List<SyncRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
