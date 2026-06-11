package com.ssairen.backend.domain.casefile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ssairen.backend.domain.casefile.dto.CaseSummaryResponse;
import com.ssairen.backend.domain.casefile.entity.CaseStatus;
import com.ssairen.backend.domain.casefile.entity.FraudCase;
import com.ssairen.backend.domain.casefile.entity.PhishingType;
import com.ssairen.backend.domain.casefile.repository.FraudCaseRepository;
import com.ssairen.backend.domain.user.entity.User;
import com.ssairen.backend.domain.user.entity.UserRole;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CaseDashboardServiceTest {

    @Mock
    private FraudCaseRepository fraudCaseRepository;

    @InjectMocks
    private CaseDashboardService caseDashboardService;

    @Test
    void status가_없으면_전체_케이스를_탐지시각_역순으로_조회한다() {
        FraudCase fraudCase = fraudCase(CaseStatus.IN_PROGRESS);
        given(fraudCaseRepository.findAllByOrderByDetectedAtDesc()).willReturn(List.of(fraudCase));

        List<CaseSummaryResponse> result = caseDashboardService.getCases(null);

        assertThat(result).hasSize(1);
        verify(fraudCaseRepository, never()).findByStatusOrderByDetectedAtDesc(any());
    }

    @Test
    void status가_있으면_해당_상태의_케이스만_조회한다() {
        FraudCase fraudCase = fraudCase(CaseStatus.COMPLETED);
        given(fraudCaseRepository.findByStatusOrderByDetectedAtDesc(CaseStatus.COMPLETED))
                .willReturn(List.of(fraudCase));

        List<CaseSummaryResponse> result = caseDashboardService.getCases(CaseStatus.COMPLETED);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(CaseStatus.COMPLETED);
        verify(fraudCaseRepository, never()).findAllByOrderByDetectedAtDesc();
    }

    @Test
    void 케이스를_대시보드_응답_DTO로_변환한다() {
        FraudCase fraudCase = fraudCase(CaseStatus.IN_PROGRESS);
        ReflectionTestUtils.setField(fraudCase, "id", 1L);
        ReflectionTestUtils.setField(fraudCase, "riskScore", 85);
        ReflectionTestUtils.setField(fraudCase, "phishingType", PhishingType.KIDNAPPING_THREAT);
        ReflectionTestUtils.setField(fraudCase, "region", "서울특별시 강남구");
        ReflectionTestUtils.setField(fraudCase, "callDurationSec", 180);
        given(fraudCaseRepository.findAllByOrderByDetectedAtDesc()).willReturn(List.of(fraudCase));

        CaseSummaryResponse response = caseDashboardService.getCases(null).get(0);

        assertThat(response.caseId()).isEqualTo(1L);
        assertThat(response.victimName()).isEqualTo("김OO");
        assertThat(response.age()).isEqualTo(71);
        assertThat(response.riskScore()).isEqualTo(85);
        assertThat(response.phishingType()).isEqualTo(PhishingType.KIDNAPPING_THREAT);
        assertThat(response.region()).isEqualTo("서울특별시 강남구");
        assertThat(response.callDurationSec()).isEqualTo(180);
    }

    private FraudCase fraudCase(CaseStatus status) {
        User victim = new User("김OO", UserRole.VICTIM, 71, "01012345678");
        FraudCase fraudCase = new FraudCase(victim, OffsetDateTime.now());
        if (status == CaseStatus.COMPLETED) {
            fraudCase.complete(OffsetDateTime.now().minusMinutes(5), OffsetDateTime.now());
        }
        return fraudCase;
    }
}