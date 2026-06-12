package com.ssairen.backend.domain.stats.controller;

import com.ssairen.backend.domain.stats.dto.AgeGroupStatResponse;
import com.ssairen.backend.domain.stats.dto.FrozenAccountStatResponse;
import com.ssairen.backend.domain.stats.dto.KeywordStatResponse;
import com.ssairen.backend.domain.stats.dto.PhoneNumberStatResponse;
import com.ssairen.backend.domain.stats.dto.StatsOverviewResponse;
import com.ssairen.backend.domain.stats.service.DashboardStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stats")
@Tag(name = "경찰 관제 대시보드 통계", description = "대시보드 통계 조회 API")
public class DashboardStatsController {

    private final DashboardStatsService dashboardStatsService;

    public DashboardStatsController(DashboardStatsService dashboardStatsService) {
        this.dashboardStatsService = dashboardStatsService;
    }

    @GetMapping("/overview")
    @Operation(
            summary = "통계 개요 조회",
            description = "전체 탐지 건수, 고위험 건수, 지급정지 계좌 수를 조회합니다."
    )
    public StatsOverviewResponse getOverview() {
        return dashboardStatsService.getOverview();
    }

    @GetMapping("/keywords")
    @Operation(
            summary = "키워드 통계 조회",
            description = "탐지된 케이스의 키워드 빈도 상위 10개를 조회합니다."
    )
    public List<KeywordStatResponse> getKeywordStats() {
        return dashboardStatsService.getKeywordStats();
    }

    @GetMapping("/age-groups")
    @Operation(
            summary = "연령대별 통계 조회",
            description = "피해자 연령대별 건수와 비율을 조회합니다."
    )
    public List<AgeGroupStatResponse> getAgeGroupStats() {
        return dashboardStatsService.getAgeGroupStats();
    }

    @GetMapping("/phone-numbers")
    @Operation(
            summary = "위험 발신번호 통계 조회",
            description = "탐지 횟수가 많은 순으로 위험 발신번호 목록을 조회합니다."
    )
    public List<PhoneNumberStatResponse> getPhoneNumberStats() {
        return dashboardStatsService.getPhoneNumberStats();
    }

    @GetMapping("/frozen-accounts")
    @Operation(
            summary = "지급정지 계좌 현황 조회",
            description = "지급정지 요청된 계좌 전체 목록을 조회합니다."
    )
    public List<FrozenAccountStatResponse> getFrozenAccountStats() {
        return dashboardStatsService.getFrozenAccountStats();
    }
}
