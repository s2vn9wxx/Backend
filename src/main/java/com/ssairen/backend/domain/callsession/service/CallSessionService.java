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
    public CallSessionResponse createSession(CreateCallSessionRequest request) {
        return callSessionRepository.findByExternalCallId(request.externalCallId())
                .map(CallSessionResponse::from)
                .orElseGet(() -> {
                    CallSession session = new CallSession(
                            UUID.randomUUID().toString(),
                            request.externalCallId(),
                            request.deviceId(),
                            request.phoneNumber(),
                            request.victim().name(),
                            request.victim().age(),
                            request.startedAt()
                    );
                    return CallSessionResponse.from(callSessionRepository.save(session));
                });
    }

    @Transactional(readOnly = true)
    public CallSessionResponse getSession(String sessionId) {
        return CallSessionResponse.from(findSession(sessionId));
    }

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
            throw new BusinessException(ErrorCode.CALL_SESSION_COMPLETED, "??? ?????? ???????? ????????");
        }

        long expectedSequence = session.getNextTranscriptSequence();
        if (sequence < expectedSequence) {
            return handlePossibleDuplicate(sessionId, chunkId, sequence, text, expectedSequence);
        }
        if (sequence > expectedSequence) {
            throw sequenceMismatch(expectedSequence);
        }
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "STT ?????? ??? ??? ????????.");
        }
        if (startedAtMs < 0 || endedAtMs < startedAtMs) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "STT ??? ??????????? ??????.");
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
                    "??? ????? sequence????? ????? ?????????.",
                    Map.of("sequence", sequence)
            );
        }
        return new TranscriptAcceptResult(chunkId, sequence, expectedSequence, true, false);
    }

    private BusinessException sequenceMismatch(long expectedSequence) {
        return new BusinessException(
                ErrorCode.TRANSCRIPT_SEQUENCE_MISMATCH,
                "STT ??? sequence?? ?????? ??????.",
                Map.of("expectedSequence", expectedSequence)
        );
    }

    private CallSession findSession(String sessionId) {
        return callSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CALL_SESSION_NOT_FOUND, "??? ???????? ????????."));
    }

    private CallSession findSessionForUpdate(String sessionId) {
        return callSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CALL_SESSION_NOT_FOUND, "??? ???????? ????????."));
    }
}

