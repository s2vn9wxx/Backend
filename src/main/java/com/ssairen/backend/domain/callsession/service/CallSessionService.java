package com.ssairen.backend.domain.callsession.service;

import com.ssairen.backend.domain.callsession.dto.CallSessionResponse;
import com.ssairen.backend.domain.callsession.dto.CreateCallSessionRequest;
import com.ssairen.backend.domain.callsession.dto.TranscriptAcceptResult;
import com.ssairen.backend.domain.callsession.entity.CallSession;
import com.ssairen.backend.domain.callsession.entity.TranscriptChunk;
import com.ssairen.backend.domain.callsession.repository.CallSessionRepository;
import com.ssairen.backend.domain.callsession.repository.TranscriptChunkRepository;
import com.ssairen.backend.global.error.BusinessException;
import com.ssairen.backend.global.error.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * Flutter 통화 세션과 STT 청크의 영속 상태를 관리한다.
 *
 * WebSocket은 연결이 끊기거나 동일 메시지가 다시 전송될 수 있으므로 메모리의 연결 상태를 신뢰하지 않는다.
 * 다음에 받을 sequence와 이미 저장된 청크는 항상 DB를 기준으로 판단하며, 같은 세션에 대한 동시 요청은
 * 비관적 잠금으로 직렬화한다.
 */
public class CallSessionService {

    private final CallSessionRepository callSessionRepository;
    private final TranscriptChunkRepository transcriptChunkRepository;
    private final long analysisThresholdCharacters;

    public CallSessionService(
            CallSessionRepository callSessionRepository,
            TranscriptChunkRepository transcriptChunkRepository,
            @Value("${ssairen.transcript.analysis-threshold-characters:1000}") long analysisThresholdCharacters
    ) {
        this.callSessionRepository = callSessionRepository;
        this.transcriptChunkRepository = transcriptChunkRepository;
        this.analysisThresholdCharacters = analysisThresholdCharacters;
    }

    @Transactional
    public CallSessionResponse createSession(CreateCallSessionRequest request, String deviceId) {
        return callSessionRepository.findByExternalCallId(request.externalCallId())
                .map(CallSessionResponse::from)
                .orElseGet(() -> {
                    CallSession session = new CallSession(
                            UUID.randomUUID().toString(),
                            request.externalCallId(),
                            deviceId,
                            request.counterpartPhoneNumber(),
                            request.startedAt()
                    );
                    return CallSessionResponse.from(callSessionRepository.save(session));
                });
    }

    @Transactional(readOnly = true)
    public CallSessionResponse getSession(String sessionId) {
        return CallSessionResponse.from(findSession(sessionId));
    }

    /**
     * 청크 저장과 nextSequence 증가를 하나의 트랜잭션으로 묶는다.
     * 반환 이후 WebSocket 핸들러가 ACK를 보내므로, ACK는 DB 반영이 성공했다는 의미가 된다.
     */
    @Transactional
    public TranscriptAcceptResult acceptTranscript(
            String sessionId,
            String chunkId,
            long sequence,
            String text,
            long startedAtMs,
            long endedAtMs,
            boolean finalChunk
    ) {
        CallSession session = findSessionForUpdate(sessionId);

        if (!session.isAcceptingTranscript()) {
            throw new BusinessException(ErrorCode.CALL_SESSION_COMPLETED, "종료 중이거나 종료된 통화 세션입니다.");
        }

        long expectedSequence = session.getNextTranscriptSequence();
        if (sequence < expectedSequence) {
            return handlePossibleDuplicate(sessionId, chunkId, sequence, text, expectedSequence);
        }
        if (sequence > expectedSequence) {
            throw sequenceMismatch(expectedSequence);
        }
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "STT 텍스트는 비어 있을 수 없습니다.");
        }
        if (startedAtMs < 0 || endedAtMs < startedAtMs) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "STT 시간 구간이 올바르지 않습니다.");
        }

        TranscriptChunk chunk = new TranscriptChunk(
                chunkId,
                session,
                sequence,
                text,
                startedAtMs,
                endedAtMs,
                finalChunk
        );
        transcriptChunkRepository.save(chunk);
        session.acceptTranscript(text.length());

        return new TranscriptAcceptResult(
                chunkId,
                sequence,
                session.getNextTranscriptSequence(),
                false,
                session.getAccumulatedTranscriptCharacters() >= analysisThresholdCharacters
        );
    }

    @Transactional
    public CallSessionResponse completeSession(String sessionId, OffsetDateTime endedAt, long lastTranscriptSequence) {
        CallSession session = findSessionForUpdate(sessionId);

        long lastStoredSequence = session.getNextTranscriptSequence() - 1;
        if (lastStoredSequence != lastTranscriptSequence) {
            throw sequenceMismatch(session.getNextTranscriptSequence());
        }
        if (session.isAcceptingTranscript()) {
            session.startCompleting(endedAt);
        }
        return CallSessionResponse.from(session);
    }

    private TranscriptAcceptResult handlePossibleDuplicate(
            String sessionId,
            String chunkId,
            long sequence,
            String text,
            long expectedSequence
    ) {
        TranscriptChunk storedChunk = transcriptChunkRepository.findByCallSessionIdAndSequence(sessionId, sequence)
                .orElseThrow(() -> sequenceMismatch(expectedSequence));

        if (!storedChunk.hasSamePayload(chunkId, text)) {
            throw new BusinessException(
                    ErrorCode.DUPLICATE_TRANSCRIPT_CONFLICT,
                    "이미 저장된 sequence와 다른 청크가 수신되었습니다.",
                    Map.of("sequence", sequence)
            );
        }
        return new TranscriptAcceptResult(chunkId, sequence, expectedSequence, true, false);
    }

    private BusinessException sequenceMismatch(long expectedSequence) {
        return new BusinessException(
                ErrorCode.TRANSCRIPT_SEQUENCE_MISMATCH,
                "STT 청크 sequence가 올바르지 않습니다.",
                Map.of("expectedSequence", expectedSequence)
        );
    }

    private CallSession findSession(String sessionId) {
        return callSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CALL_SESSION_NOT_FOUND, "통화 세션을 찾을 수 없습니다."));
    }

    private CallSession findSessionForUpdate(String sessionId) {
        return callSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CALL_SESSION_NOT_FOUND, "통화 세션을 찾을 수 없습니다."));
    }
}
