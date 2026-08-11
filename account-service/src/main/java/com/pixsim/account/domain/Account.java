package com.pixsim.account.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mapeia a MESMA tabela 'accounts' que o transaction-service usa —
 * decisão consciente de "banco compartilhado" nesta fase de transição
 * para microsserviços (ver README, seção de trade-offs). O
 * account-service é o dono conceitual da criação/consulta de contas;
 * o transaction-service continua fazendo débito/crédito diretamente por
 * enquanto, para não arriscar o fluxo core de transferência já validado
 * sob prazo apertado. Evolução natural: extrair o schema de contas para
 * um banco próprio e o transaction-service passa a chamar o
 * account-service via API em vez de acessar a tabela diretamente.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String pixKey;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Version
    private Long version;

    protected Account() {
    }

    public Account(String pixKey, BigDecimal initialBalance) {
        this.pixKey = pixKey;
        this.balance = initialBalance;
    }

    public UUID getId() { return id; }
    public String getPixKey() { return pixKey; }
    public BigDecimal getBalance() { return balance; }
    public Long getVersion() { return version; }
}
