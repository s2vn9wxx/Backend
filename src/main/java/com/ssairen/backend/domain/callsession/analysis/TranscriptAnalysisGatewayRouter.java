package com.ssairen.backend.domain.callsession.analysis;

import com.ssairen.backend.domain.callsession.analysis.dto.TranscriptAnalysisCommand;
import com.ssairen.backend.domain.callsession.analysis.dto.TranscriptAnalysisResult;
import com.ssairen.backend.global.error.BusinessException;
import com.ssairen.backend.global.error.ErrorCode;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class TranscriptAnalysisGatewayRouter implements TranscriptAnalysisGateway {

    /*
     * 설정 한 줄로 분석 공급자를 바꾸기 위한 라우터.
     * application.yaml 에서 ssairen.analysis.provider 값을 openai 또는 fastapi로만 바꾸면 된다.
     */
    private final TranscriptAnalysisGateway selectedGateway;

    public TranscriptAnalysisGatewayRouter(
            List<TranscriptAnalysisGateway> gateways,
            @Value("${ssairen.analysis.provider:openai}") String provider
    ) {
        this.selectedGateway = gateways.stream()
                .filter(gateway -> !gateway.getClass().equals(TranscriptAnalysisGatewayRouter.class))
                .filter(gateway -> gateway.providerName().equalsIgnoreCase(provider))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_REQUEST,
                        "지원하지 않는 분석 공급자입니다.",
                        java.util.Map.of("provider", provider)
                ));
    }

    @Override
    public TranscriptAnalysisResult analyze(TranscriptAnalysisCommand command) {
        return selectedGateway.analyze(command);
    }

    @Override
    public String providerName() {
        return selectedGateway.providerName();
    }
}
