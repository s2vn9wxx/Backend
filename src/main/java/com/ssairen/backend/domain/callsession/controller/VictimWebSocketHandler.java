package com.ssairen.backend.domain.callsession.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssairen.backend.domain.callsession.dto.CallSessionResponse;
import com.ssairen.backend.domain.callsession.dto.SessionCompletePayload;
import com.ssairen.backend.domain.callsession.dto.TranscriptAcceptResult;
import com.ssairen.backend.domain.callsession.dto.TranscriptChunkPayload;
import com.ssairen.backend.domain.callsession.dto.VictimClientEvent;
import com.ssairen.backend.domain.callsession.dto.VictimServerEvent;
import com.ssairen.backend.domain.callsession.service.CallSessionService;
import com.ssairen.backend.global.error.BusinessException;
import com.ssairen.backend.global.error.ErrorCode;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Component
/**
 * 피해자 Flutter 앱과 Spring Boot 사이의 양방향 WebSocket 프로토콜을 처리한다.
 *
 * Flutter가 보내는 STT 청크는 서비스에서 DB 저장이 완료된 뒤에만 ACK를 반환한다.
 * 따라서 Flutter는 ACK를 받지 못한 청크를 삭제하지 않고 재연결 시 다시 보낼 수 있으며,
 * 서버는 chunkId와 sequence를 이용해 같은 청크가 중복 저장되지 않도록 처리한다.
 *
 * FastAPI 호출과 경찰 Dashboard 전송은 이 핸들러의 책임이 아니며 이번 구현 범위에도 포함하지 않는다.
 */
public class VictimWebSocketHandler extends TextWebSocketHandler {

    private static final String SESSION_ID_ATTRIBUTE = "callSessionId";

    private final ObjectMapper objectMapper;
    private final CallSessionService callSessionService;

    public VictimWebSocketHandler(ObjectMapper objectMapper, CallSessionService callSessionService) {
        this.objectMapper = objectMapper;
        this.callSessionService = callSessionService;
    }

    /**
     * WebSocket 연결과 통화 세션을 1:1로 묶는다.
     * 연결 직후 서버가 기대하는 다음 sequence를 알려 주면 Flutter가 끊긴 지점부터 안전하게 재전송할 수 있다.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String callSessionId = extractSessionId(session);
        CallSessionResponse callSession = callSessionService.getSession(callSessionId);
        session.getAttributes().put(SESSION_ID_ATTRIBUTE, callSessionId);

        sendEvent(session, VictimServerEvent.of(
                "SESSION_READY",
                callSessionId,
                Map.of("nextTranscriptSequence", callSession.nextTranscriptSequence())
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String callSessionId = getBoundSessionId(session);

        try {
            VictimClientEvent event = objectMapper.readValue(message.getPayload(), VictimClientEvent.class);
            validateEnvelope(event, callSessionId);

            switch (event.eventType()) {
                case "TRANSCRIPT_CHUNK" -> handleTranscriptChunk(session, event);
                case "SESSION_COMPLETE" -> handleSessionComplete(session, event);
                case "PING" -> sendEvent(session, VictimServerEvent.of("PONG", callSessionId, Map.of()));
                default -> throw new BusinessException(
                        ErrorCode.INVALID_REQUEST,
                        "지원하지 않는 WebSocket 이벤트입니다.",
                        Map.of("eventType", event.eventType())
                );
            }
        } catch (BusinessException exception) {
            sendNack(session, callSessionId, exception);
        } catch (JsonProcessingException exception) {
            sendNack(session, callSessionId, new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "WebSocket JSON 메시지 형식이 올바르지 않습니다."
            ));
        }
    }

    private void handleTranscriptChunk(WebSocketSession session, VictimClientEvent event) throws IOException {
        TranscriptChunkPayload payload = objectMapper.treeToValue(event.data(), TranscriptChunkPayload.class);
        validateTranscriptPayload(payload);

        TranscriptAcceptResult result = callSessionService.acceptTranscript(
                event.sessionId(),
                payload.chunkId(),
                payload.sequence(),
                payload.text(),
                payload.startedAtMs(),
                payload.endedAtMs(),
                payload.isFinal()
        );

        sendEvent(session, VictimServerEvent.of(
                "TRANSCRIPT_ACK",
                event.sessionId(),
                Map.of(
                        "chunkId", result.chunkId(),
                        "acceptedSequence", result.acceptedSequence(),
                        "nextTranscriptSequence", result.nextSequence(),
                        "duplicate", result.duplicate(),
                        // FastAPI 연동은 이번 구현 범위가 아니므로 분석 준비 여부만 Flutter에 전달한다.
                        "analysisThresholdReached", result.analysisThresholdReached()
                )
        ));
    }

    private void handleSessionComplete(WebSocketSession session, VictimClientEvent event) throws IOException {
        SessionCompletePayload payload = objectMapper.treeToValue(event.data(), SessionCompletePayload.class);
        if (payload.endedAt() == null || payload.lastTranscriptSequence() < 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "통화 종료 데이터가 올바르지 않습니다.");
        }

        CallSessionResponse response = callSessionService.completeSession(
                event.sessionId(),
                payload.endedAt(),
                payload.lastTranscriptSequence()
        );

        sendEvent(session, VictimServerEvent.of(
                "SESSION_COMPLETE_ACK",
                event.sessionId(),
                Map.of(
                        "status", response.status(),
                        "finalAnalysisQueued", false
                )
        ));
    }

    private void validateEnvelope(VictimClientEvent event, String boundSessionId) {
        if (event.eventId() == null || event.eventId().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "eventId는 필수입니다.");
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "eventType은 필수입니다.");
        }
        if (!boundSessionId.equals(event.sessionId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "연결된 통화 세션과 메시지의 sessionId가 다릅니다.");
        }
        if (event.data() == null || event.data().isNull()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "data는 필수입니다.");
        }
    }

    private void validateTranscriptPayload(TranscriptChunkPayload payload) {
        if (payload.chunkId() == null || payload.chunkId().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "chunkId는 필수입니다.");
        }
        if (payload.sequence() < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "sequence는 1 이상이어야 합니다.");
        }
        if (!payload.isFinal()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "현재 서버는 확정된 STT 청크만 수신합니다.");
        }
    }

    /**
     * WebSocket 오류는 HTTP 상태 코드를 보낼 수 없으므로 NACK 이벤트로 변환한다.
     * sequence 오류라면 expectedSequence를 포함해 Flutter가 재전송 시작점을 결정할 수 있게 한다.
     */
    private void sendNack(WebSocketSession session, String callSessionId, BusinessException exception) throws IOException {
        sendEvent(session, VictimServerEvent.of(
                "TRANSCRIPT_NACK",
                callSessionId,
                Map.of(
                        "reason", exception.getErrorCode().name(),
                        "message", exception.getMessage(),
                        "details", exception.getDetails()
                )
        ));
    }

    private String extractSessionId(WebSocketSession session) {
        String sessionId = UriComponentsBuilder.fromUri(session.getUri())
                .build()
                .getQueryParams()
                .getFirst("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "WebSocket 연결에 sessionId가 필요합니다.");
        }
        return sessionId;
    }

    private String getBoundSessionId(WebSocketSession session) {
        Object sessionId = session.getAttributes().get(SESSION_ID_ATTRIBUTE);
        if (sessionId == null) {
            throw new BusinessException(ErrorCode.CALL_SESSION_NOT_FOUND, "WebSocket에 연결된 통화 세션이 없습니다.");
        }
        return sessionId.toString();
    }

    private void sendEvent(WebSocketSession session, VictimServerEvent event) throws IOException {
        // 동일 연결로 ACK와 분석 이벤트 등이 동시에 전송될 수 있어 sendMessage 호출을 직렬화한다.
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }
}
