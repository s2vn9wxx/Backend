package com.ssairen.backend.domain.callsession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssairen.backend.domain.callsession.dto.CallSessionResponse;
import com.ssairen.backend.domain.callsession.dto.CreateCallSessionRequest;
import com.ssairen.backend.domain.callsession.dto.TranscriptAcceptResult;
import com.ssairen.backend.domain.callsession.entity.CallSessionStatus;
import com.ssairen.backend.domain.callsession.repository.CallSessionRepository;
import com.ssairen.backend.domain.callsession.repository.TranscriptChunkRepository;
import com.ssairen.backend.global.error.BusinessException;
import com.ssairen.backend.global.error.ErrorCode;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "ssairen.transcript.analysis-threshold-characters=5")
class CallSessionServiceTest {

    @Autowired
    private CallSessionService callSessionService;

    @Autowired
    private CallSessionRepository callSessionRepository;

    @Autowired
    private TranscriptChunkRepository transcriptChunkRepository;

    @BeforeEach
    void clearDatabase() {
        transcriptChunkRepository.deleteAll();
        callSessionRepository.deleteAll();
    }

    @Test
    void ???_???_???_ID?????????????_???????????() {
        CreateCallSessionRequest request = request("external-call-1");

        CallSessionResponse first = callSessionService.createSession(request);
        CallSessionResponse second = callSessionService.createSession(request);

        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(callSessionRepository.count()).isEqualTo(1);
    }

    @Test
    void STT_??????????????????????_sequence????????() {
        String sessionId = createSession();

        TranscriptAcceptResult result = callSessionService.acceptTranscript(
                sessionId,
                "chunk-1",
                1,
                "??????????????,
                0,
                1000,
                true
        );

        assertThat(result.acceptedSequence()).isEqualTo(1);
        assertThat(result.nextSequence()).isEqualTo(2);
        assertThat(result.analysisThresholdReached()).isTrue();
        assertThat(transcriptChunkRepository.count()).isEqualTo(1);
    }

    @Test
    void ?????????????????????_???????_???_ACK_?????_???????????() {
        String sessionId = createSession();
        callSessionService.acceptTranscript(sessionId, "chunk-1", 1, "??? ?????, 0, 1000, true);

        TranscriptAcceptResult duplicate = callSessionService.acceptTranscript(
                sessionId,
                "chunk-1",
                1,
                "??? ?????,
                0,
                1000,
                true
        );

        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.nextSequence()).isEqualTo(2);
        assertThat(transcriptChunkRepository.count()).isEqualTo(1);
    }

    @Test
    void ???_sequence???_?????????????() {
        String sessionId = createSession();

        assertThatThrownBy(() -> callSessionService.acceptTranscript(
                sessionId,
                "chunk-2",
                2,
                "??????? ?????,
                1000,
                2000,
                true
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TRANSCRIPT_SEQUENCE_MISMATCH);
                    assertThat(exception.getDetails()).containsEntry("expectedSequence", 1L);
                });
    }

    @Test
    void ?????sequence???_??????????????????_????????_???_???????????() {
        String sessionId = createSession();
        callSessionService.acceptTranscript(sessionId, "chunk-1", 1, "????????", 0, 1000, true);

        CallSessionResponse completed = callSessionService.completeSession(sessionId, OffsetDateTime.now(), 1);

        assertThat(completed.status()).isEqualTo(CallSessionStatus.COMPLETING);
        assertThatThrownBy(() -> callSessionService.acceptTranscript(
                sessionId,
                "chunk-2",
                2,
                "??? ?????",
                1000,
                2000,
                true
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CALL_SESSION_COMPLETED)
                );
    }

    private String createSession() {
        return callSessionService.createSession(request("external-call-" + System.nanoTime())).sessionId();
    }

    private CreateCallSessionRequest request(String externalCallId) {
        return new CreateCallSessionRequest(
                externalCallId,
                "device-1",
                OffsetDateTime.now(),
                "01012345678",
                new CreateCallSessionRequest.VictimRequest("??OO", 71)
        );
    }
}

