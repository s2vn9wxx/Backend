package com.ssairen.backend.domain.callsession.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.ssairen.backend.domain.callsession.analysis.dto.TranscriptAnalysisCommand;
import com.ssairen.backend.domain.callsession.analysis.dto.TranscriptAnalysisResult;
import com.ssairen.backend.domain.casefile.entity.PhishingType;
import com.ssairen.backend.global.error.BusinessException;
import com.ssairen.backend.global.error.ErrorCode;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OpenAiTranscriptAnalysisGateway implements TranscriptAnalysisGateway {

    /*
     * OpenAI는 기본 우선순위 분석기다.
     * 다만 FastAPI로 스위칭했을 때 OPENAI_API_KEY가 없어도 앱이 뜰 수 있도록
     * 클라이언트는 빈 생성 시점이 아니라 실제 analyze 호출 시점에 lazy 초기화한다.
     */
    private final ObjectMapper objectMapper;
    private final String model;
    private final String prompt;
    private OpenAIClient openAIClient;

    public OpenAiTranscriptAnalysisGateway(
            ObjectMapper objectMapper,
            @Value("${ssairen.analysis.openai.model:gpt-4o}") String model,
            @Value("${ssairen.analysis.openai.prompt:보이스피싱 탐지 보조 AI로서 입력 텍스트를 분석해 JSON만 반환하세요. 필드는 riskScore(0~100 정수), phishingType(AGENCY_IMPERSONATION|ACCOUNT_TRANSFER_INDUCEMENT|KIDNAPPING_THREAT|REMOTE_APP_INSTALLATION|UNKNOWN), aiSummary(문자열), keywords(문자열 배열) 입니다.}") String prompt
    ) {
        this.objectMapper = objectMapper;
        this.model = model;
        this.prompt = prompt;
    }

    @Override
    public TranscriptAnalysisResult analyze(TranscriptAnalysisCommand command) {
        try {
            String input = """
                    modelHint: %s
                    %s

                    sessionId: %s
                    sequence: %d
                    victimName: %s
                    victimAge: %s
                    victimPhone: %s
                    transcript:
                    %s
                    """.formatted(
                    model,
                    prompt,
                    command.sessionId(),
                    command.sequence(),
                    command.victimName(),
                    command.victimAge(),
                    command.victimPhone(),
                    command.transcript()
            );

            Response response = client().responses().create(
                    ResponseCreateParams.builder()
                            .model(ChatModel.GPT_4O)
                            .input(input)
                            .build()
            );

            String json = response.output().stream()
                    .flatMap(item -> item.message().stream())
                    .flatMap(message -> message.content().stream())
                    .flatMap(content -> content.outputText().stream())
                    .map(outputText -> outputText.text())
                    .reduce("", String::concat)
                    .trim();

            OpenAiAnalysisPayload payload = objectMapper.readValue(json, OpenAiAnalysisPayload.class);
            return new TranscriptAnalysisResult(
                    clampRisk(payload.riskScore),
                    resolveType(payload.phishingType),
                    payload.aiSummary,
                    payload.keywords == null ? List.of() : payload.keywords,
                    providerName()
            );
        } catch (Exception exception) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "OpenAI 분석 요청에 실패했습니다.",
                    java.util.Map.of("provider", providerName(), "message", exception.getMessage())
            );
        }
    }

    @Override
    public String providerName() {
        return "openai";
    }

    private OpenAIClient client() {
        if (this.openAIClient == null) {
            this.openAIClient = OpenAIOkHttpClient.fromEnv();
        }
        return this.openAIClient;
    }

    private int clampRisk(Integer riskScore) {
        if (riskScore == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, riskScore));
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiAnalysisPayload(
            Integer riskScore,
            String phishingType,
            String aiSummary,
            List<String> keywords
    ) {
    }
}
