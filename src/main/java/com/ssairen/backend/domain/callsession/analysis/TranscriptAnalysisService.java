package com.ssairen.backend.domain.callsession.analysis;

import com.ssairen.backend.domain.callsession.analysis.dto.TranscriptAnalysisCommand;
import com.ssairen.backend.domain.callsession.analysis.dto.TranscriptAnalysisResult;
import com.ssairen.backend.domain.callsession.entity.CallSession;
import com.ssairen.backend.domain.callsession.repository.CallSessionRepository;
import com.ssairen.backend.global.error.BusinessException;
import com.ssairen.backend.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TranscriptAnalysisService {

    /*
     * 이 서비스는 STT 청크를 외부 분석기(OpenAI 또는 FastAPI)에 보내고,
     * 응답 결과를 case 엔티티에 반영한 뒤 Flutter에 내려줄 값으로 정리한다.
     */
    private final CallSessionRepository callSessionRepository;
    private final TranscriptAnalysisGateway transcriptAnalysisGateway;

    public TranscriptAnalysisService(
            CallSessionRepository callSessionRepository,
            TranscriptAnalysisGateway transcriptAnalysisGateway
    ) {
        this.callSessionRepository = callSessionRepository;
        this.transcriptAnalysisGateway = transcriptAnalysisGateway;
    }

    @Transactional
    public TranscriptAnalysisResult analyzeChunk(String sessionId, long sequence, String transcript) {
        CallSession session = callSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CALL_SESSION_NOT_FOUND, "통화 세션을 찾을 수 없습니다."));

        TranscriptAnalysisResult result = transcriptAnalysisGateway.analyze(new TranscriptAnalysisCommand(
                sessionId,
                sequence,
                transcript,
                session.getVictim().getName(),
                session.getVictim().getAge(),
                session.getVictim().getPhone()
        ));

        // 가장 최근 청크의 분석 결과를 현재 case 상태에 반영한다.
        session.getFraudCase().applyAnalysisResult(
                result.riskScore(),
                result.phishingType(),
                result.aiSummary(),
                result.keywords()
        );

        return result;
    }
}
