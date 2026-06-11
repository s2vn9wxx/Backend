package com.ssairen.backend.domain.casefile.service;

import com.ssairen.backend.domain.casefile.dto.CaseSummaryResponse;
import com.ssairen.backend.domain.casefile.entity.CaseStatus;
import com.ssairen.backend.domain.casefile.entity.FraudCase;
import com.ssairen.backend.domain.casefile.repository.FraudCaseRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaseDashboardService {

    private final FraudCaseRepository fraudCaseRepository;

    public CaseDashboardService(FraudCaseRepository fraudCaseRepository) {
        this.fraudCaseRepository = fraudCaseRepository;
    }

    @Transactional(readOnly = true)
    public List<CaseSummaryResponse> getCases(CaseStatus status) {
        List<FraudCase> cases = (status != null)
                ? fraudCaseRepository.findByStatusOrderByDetectedAtDesc(status)
                : fraudCaseRepository.findAllByOrderByDetectedAtDesc();

        return cases.stream()
                .map(CaseSummaryResponse::from)
                .toList();
    }
}