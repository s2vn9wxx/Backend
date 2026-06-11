package com.ssairen.backend.domain.casefile.controller;

import com.ssairen.backend.domain.casefile.dto.CaseSummaryResponse;
import com.ssairen.backend.domain.casefile.entity.CaseStatus;
import com.ssairen.backend.domain.casefile.service.CaseDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard/cases")
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
}