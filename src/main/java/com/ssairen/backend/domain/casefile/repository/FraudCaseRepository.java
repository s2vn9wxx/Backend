package com.ssairen.backend.domain.casefile.repository;

import com.ssairen.backend.domain.casefile.entity.CaseStatus;
import com.ssairen.backend.domain.casefile.entity.FraudCase;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudCaseRepository extends JpaRepository<FraudCase, Long> {

    List<FraudCase> findByStatusOrderByDetectedAtDesc(CaseStatus status);

    List<FraudCase> findAllByOrderByDetectedAtDesc();

    long countByStatus(CaseStatus status);
}
