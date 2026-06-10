package com.ssairen.backend.domain.callsession.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;

@Entity
@Table(name = "call_sessions")
public class CallSession {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true, length = 100)
    private String externalCallId;

    @Column(nullable = false, length = 100)
    private String deviceId;

    @Column(length = 30)
    private String counterpartPhoneNumber;

    @Column(nullable = false)
    private OffsetDateTime startedAt;

    private OffsetDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CallSessionStatus status;

    @Column(nullable = false)
    private long nextTranscriptSequence;

    @Column(nullable = false)
    private long accumulatedTranscriptCharacters;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected CallSession() {
    }

    public CallSession(
            String id,
            String externalCallId,
            String deviceId,
            String counterpartPhoneNumber,
            OffsetDateTime startedAt
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        this.id = id;
        this.externalCallId = externalCallId;
        this.deviceId = deviceId;
        this.counterpartPhoneNumber = counterpartPhoneNumber;
        this.startedAt = startedAt;
        this.status = CallSessionStatus.ACTIVE;
        this.nextTranscriptSequence = 1L;
        this.accumulatedTranscriptCharacters = 0L;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 청크 저장이 성공한 뒤에만 호출한다.
     * 이 메서드가 다음 sequence를 전진시키므로 DB 저장 실패 시 ACK가 먼저 나가는 상황을 막을 수 있다.
     */
    public void acceptTranscript(int textLength) {
        this.nextTranscriptSequence++;
        this.accumulatedTranscriptCharacters += textLength;
        this.updatedAt = OffsetDateTime.now();
    }

    public void startCompleting(OffsetDateTime endedAt) {
        this.status = CallSessionStatus.COMPLETING;
        this.endedAt = endedAt;
        this.updatedAt = OffsetDateTime.now();
    }

    public boolean isAcceptingTranscript() {
        return this.status == CallSessionStatus.ACTIVE;
    }

    public String getId() {
        return id;
    }

    public String getExternalCallId() {
        return externalCallId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public CallSessionStatus getStatus() {
        return status;
    }

    public long getNextTranscriptSequence() {
        return nextTranscriptSequence;
    }

    public long getAccumulatedTranscriptCharacters() {
        return accumulatedTranscriptCharacters;
    }
}
