package com.ssairen.backend.domain.callsession.application;

import com.ssairen.backend.domain.callsession.api.dto.CallSessionResponse;
import com.ssairen.backend.domain.callsession.api.dto.CreateCallSessionRequest;
import com.ssairen.backend.domain.callsession.api.dto.SessionCompletionResult;
import com.ssairen.backend.domain.callsession.entity.CallSession;
import com.ssairen.backend.domain.callsession.entity.TranscriptChunk;
import com.ssairen.backend.domain.callsession.repository.CallSessionRepository;
import com.ssairen.backend.domain.callsession.repository.TranscriptChunkRepository;
import com.ssairen.backend.domain.callsession.websocket.dto.TranscriptAcceptResult;
import com.ssairen.backend.domain.casefile.entity.FraudCase;
import com.ssairen.backend.domain.casefile.repository.FraudCaseRepository;
import com.ssairen.backend.domain.user.entity.User;
import com.ssairen.backend.domain.user.entity.UserRole;
import com.ssairen.backend.domain.user.repository.UserRepository;
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

    /*
     * 이 서비스는 Flutter와 주고받는 "통화 세션"의 수명주기를 관리한다.
     * 실제 업무 중심 데이터는 ERD에 맞춰 users, cases 테이블에 저장하고,
     * call_sessions, transcript_chunks 는 실시간 업로드 순서 제어를 위한 기술 테이블로 유지한다.
     * 덕분에 피해자/사건 도메인과 모바일 전송 제어 로직을 분리할 수 있다.
     */
    private final CallSessionRepository callSessionRepository;
    private final TranscriptChunkRepository transcriptChunkRepository;
    private final UserRepository userRepository;
    private final FraudCaseRepository fraudCaseRepository;

    /*
     * transcript가 어느 정도 누적되었을 때 "분석해볼 만한 길이인지" 판단하는 기준이다.
     * 현재는 누적 문자 수로만 판단하고,
     * 실제 OpenAI/FastAPI 호출 자체는 WebSocket 수신 직후 청크 단위로 수행한다.
     */
    private final long analysisThresholdCharacters;

    public CallSessionService(
            CallSessionRepository callSessionRepository,
            TranscriptChunkRepository transcriptChunkRepository,
            UserRepository userRepository,
            FraudCaseRepository fraudCaseRepository,
            @Value("${ssairen.transcript.analysis-threshold-characters:1000}") long analysisThresholdCharacters
    ) {
        this.callSessionRepository = callSessionRepository;
        this.transcriptChunkRepository = transcriptChunkRepository;
        this.userRepository = userRepository;
        this.fraudCaseRepository = fraudCaseRepository;
        this.analysisThresholdCharacters = analysisThresholdCharacters;
    }

    @Transactional
    public CallSessionResponse createSession(CreateCallSessionRequest request) {
        return callSessionRepository.findByExternalCallId(request.externalCallId())
                .map(CallSessionResponse::from)
                .orElseGet(() -> {
                    /*
                     * 세션을 만들기 전에 피해자(user)와 사건(case)을 먼저 생성한다.
                     * 이렇게 해야 transcript 분석 결과가 들어왔을 때
                     * 별도 변환 없이 바로 ERD 기준 엔티티에 반영할 수 있다.
                     */
                    User victim = resolveVictim(request);
                    FraudCase fraudCase = fraudCaseRepository.save(new FraudCase(victim, request.startedAt()));
                    CallSession session = new CallSession(
                            UUID.randomUUID().toString(),
                            request.externalCallId(),
                            request.deviceId(),
                            victim,
                            fraudCase,
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

        // 종료된 세션은 더 이상 transcript를 받지 않는다.
        if (!session.isAcceptingTranscript()) {
            throw new BusinessException(ErrorCode.CALL_SESSION_COMPLETED, "종료 중이거나 이미 종료된 통화 세션입니다.");
        }

        long expectedSequence = session.getNextTranscriptSequence();

        /*
         * 기대 sequence보다 작은 값은 대체로 재전송이다.
         * 같은 chunk가 이미 저장된 정상 재전송인지 먼저 확인하고,
         * 실제 충돌일 때만 예외를 던져 Flutter가 복구 판단을 하게 만든다.
         */
        if (sequence < expectedSequence) {
            return handlePossibleDuplicate(sessionId, chunkId, sequence, text, expectedSequence);
        }

        // 기대 sequence보다 큰 값이면 중간 청크 유실이므로 복구가 필요하다.
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

        /*
         * 저장이 끝난 뒤에만 sequence와 누적 문자 수를 증가시킨다.
         * 그래야 DB 저장은 실패했는데 ACK만 나간 상태를 막을 수 있다.
         */
        session.acceptTranscript(endedAtMs, text.length());

        return new TranscriptAcceptResult(
                chunkId,
                sequence,
                session.getNextTranscriptSequence(),
                false,
                session.getAccumulatedTranscriptCharacters() >= analysisThresholdCharacters
        );
    }

    @Transactional
    public SessionCompletionResult completeSession(String sessionId, OffsetDateTime endedAt, long lastTranscriptSequence) {
        CallSession session = findSessionForUpdate(sessionId);

        /*
         * Flutter가 마지막으로 보냈다고 주장하는 sequence와
         * 서버가 실제 저장한 마지막 sequence가 다르면 종료 직전 유실이 발생한 것이다.
         * 이 경우 세션 종료를 허용하지 않고 먼저 sequence 불일치를 알려준다.
         */
        long lastStoredSequence = session.getNextTranscriptSequence() - 1;
        if (lastStoredSequence != lastTranscriptSequence) {
            throw sequenceMismatch(session.getNextTranscriptSequence());
        }

        boolean finalAnalysisQueued = false;
        if (session.isAcceptingTranscript()) {
            /*
             * 마지막 분석이 필요한지 여부를 세션 상태에 남겨 둔다.
             * 지금은 단순 플래그지만, 이후 배치나 이벤트 기반 후처리로 바뀌어도
             * "마무리 분석 대기 중인 통화"를 도메인 상태만으로 판별할 수 있다.
             */
            finalAnalysisQueued = session.queueFinalAnalysisIfNeeded(lastStoredSequence);

            // 세션 종료와 함께 연결된 사건도 완료 상태로 정리한다.
            session.complete(endedAt);
        }

        return new SessionCompletionResult(CallSessionResponse.from(session), finalAnalysisQueued);
    }

    private TranscriptAcceptResult handlePossibleDuplicate(
            String sessionId,
            String chunkId,
            long sequence,
            String text,
            long expectedSequence
    ) {
        /*
         * 같은 sequence가 이미 저장돼 있는지 먼저 확인한다.
         * 없다면 단순 재전송이 아니라 클라이언트와 서버 상태가 어긋난 것이다.
         */
        TranscriptChunk storedChunk = transcriptChunkRepository.findByCallSessionIdAndSequence(sessionId, sequence)
                .orElseThrow(() -> sequenceMismatch(expectedSequence));

        /*
         * sequence는 같지만 payload가 다르면 정상 재전송이 아니라 충돌이다.
         * 이 상황을 허용하면 동일 sequence에 서로 다른 STT 결과가 섞일 수 있으므로 거절한다.
         */
        if (!storedChunk.hasSamePayload(chunkId, text)) {
            throw new BusinessException(
                    ErrorCode.DUPLICATE_TRANSCRIPT_CONFLICT,
                    "이미 저장된 sequence에 다른 chunk payload가 도착했습니다.",
                    Map.of("sequence", sequence)
            );
        }

        return new TranscriptAcceptResult(chunkId, sequence, expectedSequence, true, false);
    }

    private User resolveVictim(CreateCallSessionRequest request) {
        /*
         * 전화번호가 있으면 같은 피해자일 가능성이 높으므로
         * 이름 + 역할 + 전화번호로 먼저 조회해 기존 row를 재사용한다.
         * 세션이 여러 번 열려도 피해자 엔티티가 과도하게 중복 생성되지 않게 하기 위함이다.
         */
        if (request.phoneNumber() != null && !request.phoneNumber().isBlank()) {
            return userRepository.findFirstByNameAndRoleAndPhone(
                            request.victim().name(),
                            UserRole.VICTIM,
                            request.phoneNumber()
                    )
                    .map(existing -> {
                        // 기존 피해자라면 최신 나이/전화번호로 프로필을 보정한다.
                        existing.updateVictimProfile(request.victim().age(), request.phoneNumber());
                        return existing;
                    })
                    .orElseGet(() -> userRepository.save(new User(
                            request.victim().name(),
                            UserRole.VICTIM,
                            request.victim().age(),
                            request.phoneNumber()
                    )));
        }

        // 전화번호가 없으면 중복 판별 근거가 약하므로 새 피해자 row를 생성한다.
        return userRepository.save(new User(
                request.victim().name(),
                UserRole.VICTIM,
                request.victim().age(),
                null
        ));
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
