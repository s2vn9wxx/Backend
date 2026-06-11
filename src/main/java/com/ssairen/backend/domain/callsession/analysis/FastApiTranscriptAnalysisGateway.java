package com.ssairen.backend.domain.callsession.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ssairen.backend.domain.callsession.analysis.dto.TranscriptAnalysisCommand;
import com.ssairen.backend.domain.callsession.analysis.dto.TranscriptAnalysisResult;
import com.ssairen.backend.domain.casefile.entity.PhishingType;
import com.ssairen.backend.global.error.BusinessException;
import com.ssairen.backend.global.error.ErrorCode;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FastApiTranscriptAnalysisGateway implements TranscriptAnalysisGateway {

    private final RestClient restClient;
    private final String analysisPath;

    public FastApiTranscriptAnalysisGateway(
            @Value("${ssairen.analysis.fastapi.url:http://localhost:8000}") String baseUrl,
            @Value("${ssairen.analysis.fastapi.path:/api/v1/transcript-analysis}") String analysisPath
    ) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.analysisPath = analysisPath;
    }

    @Override
    public TranscriptAnalysisResult analyze(TranscriptAnalysisCommand command) {
        try {
            FastApiAnalysisResponse response = restClient.post()
                    .uri(analysisPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new FastApiAnalysisRequest(
                            command.sessionId(),
                            command.sequence(),
                            command.transcript(),
                            command.victimName(),
                            command.victimAge(),
                            command.victimPhone()
                    ))
                    .retrieve()
                    .body(FastApiAnalysisResponse.class);

            if (response == null) {
                throw new IllegalStateException("빈 응답");
            }

            return new TranscriptAnalysisResult(
                    Math.max(0, Math.min(100, response.riskScore == null ? 0 : response.riskScore)),
                    resolveType(response.phishingType),
                    response.aiSummary,
                    response.keywords == null ? List.of() : response.keywords,
                    providerName()
            );
        } catch (Exception exception) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "FastAPI 분석 요청에 실패했습니다.",
                    java.util.Map.of("provider", providerName(), "message", exception.getMessage())
            );
        }
    }

    @Override
    public String providerName() {
        return "fastapi";
    }

    private PhishingType resolveType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return PhishingType.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record FastApiAnalysisRequest(
            String sessionId,
            long sequence,
            String transcript,
            String victimName,
            Integer victimAge,
            String victimPhone
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FastApiAnalysisResponse(
            Integer riskScore,
            String phishingType,
            String aiSummary,
            List<String> keywords
    ) {
    }
}
