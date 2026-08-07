package com.pixsim.transaction.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entidade de conta bancária.
 *
 * Controle de concorrência: usamos @Version (lock otimista) como estratégia
 * PRIMÁRIA porque em um sistema de alta vazão, lock pessimista (SELECT FOR UPDATE)
 * serializa acessos à mesma conta e vira gargalo em contas "quentes" (ex: PIX
 * recebido de muitos pagadores simultâneos, como uma loja).
 *
 * Trade-off: lock otimista falha sob alta contenção na MESMA conta e exige
 * retry. Para contas de altíssimo volume, o TransactionService abaixo usa
 * uma estratégia híbrida (ver comentário no service).
 */
@Entity
@Table(name = "accounts", indexes = {
        @Index(name = "idx_account_pix_key", columnList = "pixKey", unique = true)
})
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String pixKey;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    /**
     * Lock otimista: o Hibernate incrementa esta coluna a cada UPDATE e
     * valida no WHERE. Se outra transação já alterou a linha,
     * lança OptimisticLockException — tratado no service com retry.
     */
    @Version
    private Long version;

    protected Account() {
    }

    public Account(String pixKey, BigDecimal initialBalance) {
        this.pixKey = pixKey;
        this.balance = initialBalance;
    }

    public void debit(BigDecimal amount) {
        if (this.balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(this.id, amount, this.balance);
        }
        this.balance = this.balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    public UUID getId() { return id; }
    public String getPixKey() { return pixKey; }
    public BigDecimal getBalance() { return balance; }
    public Long getVersion() { return version; }
}
