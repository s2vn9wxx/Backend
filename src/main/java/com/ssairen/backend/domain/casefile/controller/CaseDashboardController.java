package com.ssairen.backend.domain.casefile.controller;

import com.ssairen.backend.domain.casefile.dto.CaseDetailResponse;
import com.ssairen.backend.domain.casefile.dto.CaseStatusUpdateRequest;
import com.ssairen.backend.domain.casefile.dto.CaseStatusUpdateResponse;
import com.ssairen.backend.domain.casefile.dto.CaseSummaryMetricsResponse;
import com.ssairen.backend.domain.casefile.dto.CaseSummaryResponse;
import com.ssairen.backend.domain.casefile.entity.CaseStatus;
import com.ssairen.backend.domain.casefile.service.CaseDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cases")
@Tag(name = "경찰 관제 대시보드", description = "케이스 목록 조회 API")
public class CaseDashboardController {

    private final CaseDashboardService caseDashboardService;

    public CaseDashboardController(CaseDashboardService caseDashboardService) {
        this.caseDashboardService = caseDashboardService;
    }

    @GetMapping
    @Operation(
            summary = "진행중 케이스 목록 조회",
            description = "경찰 관제 대시보드에서 케이스 목록을 조회합니다. status로 필터링할 수 있습니다."
    )
    public List<CaseSummaryResponse> getCases(
            @Parameter(description = "케이스 진행 상태 필터", example = "IN_PROGRESS")
            @RequestParam(required = false) CaseStatus status
    ) {
        return caseDashboardService.getCases(status);
    }

    @GetMapping("/summary")
    @Operation(
            summary = "상단 요약 지표 조회",
            description = "진행중/완료 케이스 수와 평균 대응 시간(초)을 조회합니다."
    )
    public CaseSummaryMetricsResponse getSummary() {
        return caseDashboardService.getSummaryMetrics();
    }

    @GetMapping("/{caseId}")
    @Operation(
            summary = "케이스 상세 조회",
            description = "AI 통화 분석 요약, 위치, 대응 프로세스 진행 상황을 조회합니다."
    )
    public CaseDetailResponse getCaseDetail(
            @Parameter(description = "케이스 ID", example = "1")
            @PathVariable Long caseId
    ) {
        return caseDashboardService.getCaseDetail(caseId);
    }

    @PatchMapping("/{caseId}/status")
    @Operation(
            summary = "케이스 상태 변경",
            description = "케이스를 종료하거나 진행 상태를 변경합니다."
    )
    public CaseStatusUpdateResponse updateStatus(
            @Parameter(description = "케이스 ID", example = "1")
            @PathVariable Long caseId,
            @Valid @RequestBody CaseStatusUpdateRequest request
    ) {
        return caseDashboardService.updateStatus(caseId, request.status());
    }
}