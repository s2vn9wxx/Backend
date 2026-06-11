package com.ssairen.backend.domain.callsession.api;

import com.ssairen.backend.domain.callsession.api.dto.CallSessionResponse;
import com.ssairen.backend.domain.callsession.api.dto.CreateCallSessionRequest;
import com.ssairen.backend.domain.callsession.application.CallSessionService;
import com.ssairen.backend.global.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flutter 앱이 가장 먼저 진입하는 통화 세션 REST 컨트롤러다.
 * 세션 생성/재조회만 담당하고, 실시간 transcript 수신은 WebSocket으로 분리한다.
 */
@RestController
@RequestMapping("/api/mobile/call-sessions")
@Tag(name = "통화 세션", description = "Flutter 피해자 앱용 통화 모니터링 세션 API")
public class CallSessionController {

    private final CallSessionService callSessionService;

    public CallSessionController(CallSessionService callSessionService) {
        this.callSessionService = callSessionService;
    }

    @PostMapping
    @Operation(
            summary = "통화 세션 생성",
            description = "Flutter 앱이 통화 감지를 시작할 때 호출한다. 같은 externalCallId가 이미 존재하면 기존 세션을 그대로 반환한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "통화 세션 생성 또는 기존 세션 반환"),
            @ApiResponse(
                    responseCode = "400",
                    description = "요청 값 검증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<CallSessionResponse> createSession(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Flutter가 생성한 외부 통화 ID와 피해자 기본 정보",
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = CreateCallSessionRequest.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "externalCallId": "device-call-001",
                                      "deviceId": "victim-device-001",
                                      "startedAt": "2026-06-10T15:20:00+09:00",
                                      "phoneNumber": "01012345678",
                                      "victim": {
                                        "name": "김OO",
                                        "age": 71
                                      }
                                    }
                                    """)
                    )
            )
            @Valid @RequestBody CreateCallSessionRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(callSessionService.createSession(request));
    }

    @GetMapping("/{sessionId}")
    @Operation(
            summary = "통화 세션 상태 조회",
            description = "Flutter 앱이 재실행되거나 WebSocket 재연결 전에 현재 세션 상태와 다음 sequence 값을 복구할 때 사용한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "통화 세션 상태 조회 성공"),
            @ApiResponse(
                    responseCode = "404",
                    description = "통화 세션을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public CallSessionResponse getSession(
            @Parameter(description = "통화 세션 ID", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String sessionId
    ) {
        return callSessionService.getSession(sessionId);
    }
}
