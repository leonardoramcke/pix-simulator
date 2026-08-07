package com.pixsim.transaction.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pix_transactions", indexes = {
        // endToEndId é a chave de idempotência do Pix real (BACEN) — únique
        // garante que reenvios de rede não dupliquem a transferência.
        @Index(name = "idx_end_to_end_id", columnList = "endToEndId", unique = true)
})
public class PixTransaction {

    public enum Status { PENDING, COMPLETED, FAILED, REVERSED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String endToEndId; // idempotency key

    @Column(nullable = false)
    private UUID sourceAccountId;

    @Column(nullable = false)
    private UUID targetAccountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    private String failureReason;

    protected PixTransaction() {
    }

    public PixTransaction(String endToEndId, UUID sourceAccountId, UUID targetAccountId, BigDecimal amount) {
        this.endToEndId = endToEndId;
        this.sourceAccountId = sourceAccountId;
        this.targetAccountId = targetAccountId;
        this.amount = amount;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
    }

    public void markCompleted() {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEndToEndId() { return endToEndId; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getTargetAccountId() { return targetAccountId; }
    public BigDecimal getAmount() { return amount; }
    public Status getStatus() { return status; }
}
