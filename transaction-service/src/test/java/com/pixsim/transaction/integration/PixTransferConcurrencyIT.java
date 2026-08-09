package com.pixsim.transaction.integration;

import com.pixsim.transaction.application.PixTransferService;
import com.pixsim.transaction.domain.Account;
import com.pixsim.transaction.infrastructure.AccountRepository;
import com.pixsim.transaction.infrastructure.PixTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de integração de ponta a ponta: usa o Postgres REAL já disponível
 * via docker-compose (mesmo usado no desenvolvimento manual), rodando as
 * migrations Flyway de verdade contra ele.
 *
 * NOTA DE AMBIENTE: originalmente este teste usava Testcontainers para
 * subir um Postgres efêmero e isolado automaticamente — a abordagem mais
 * correta para CI/CD. No entanto, o cliente Docker embutido no
 * Testcontainers (docker-java) apresentou incompatibilidade persistente
 * com esta instalação específica do Docker Desktop no Windows (erro
 * "Could not find a valid Docker environment" mesmo com Docker
 * funcionando normalmente via CLI/curl — um problema documentado em
 * algumas versões do Docker Desktop no Windows). Como alternativa
 * pragmática, o teste passou a usar a infraestrutura já provisionada
 * pelo docker-compose.yml do projeto. Isso exige que 'docker compose up'
 * esteja rodando antes deste teste — trade-off aceitável para
 * desenvolvimento local; a suíte de CI (GitHub Actions) pode reverter
 * para Testcontainers, ambiente Linux onde esse problema não ocorre.
 *
 * O teste mais importante aqui é o de CONCORRÊNCIA: dispara várias
 * transferências simultâneas na MESMA conta de destino, simulando o
 * cenário real de uma conta recebendo muitos pagamentos ao mesmo tempo
 * (ex: um PIX de loja popular). Isso valida que:
 *   1. O lock otimista + retry realmente protegem o saldo (sem
 *      condição de corrida silenciosa);
 *   2. Nenhuma transferência é perdida nem duplicada sob concorrência.
 */
@SpringBootTest
class PixTransferConcurrencyIT {

    @Autowired
    private PixTransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PixTransactionRepository transactionRepository;

    private UUID hotAccountId;   // conta "quente": alvo de muitas transferências simultâneas
    private List<UUID> payerIds; // várias contas pagadoras diferentes

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        Account hotAccount = accountRepository.save(
                new Account("loja-popular@pix.com", BigDecimal.ZERO));
        hotAccountId = hotAccount.getId();

        payerIds = List.of(
                accountRepository.save(new Account("pagador1@pix.com", new BigDecimal("1000.00"))).getId(),
                accountRepository.save(new Account("pagador2@pix.com", new BigDecimal("1000.00"))).getId(),
                accountRepository.save(new Account("pagador3@pix.com", new BigDecimal("1000.00"))).getId(),
                accountRepository.save(new Account("pagador4@pix.com", new BigDecimal("1000.00"))).getId(),
                accountRepository.save(new Account("pagador5@pix.com", new BigDecimal("1000.00"))).getId()
        );
    }

    @Test
    void deveManterConsistenciaDeSaldoSobTransferenciasConcorrentesNaMesmaConta() throws InterruptedException {
        int transferenciasPorPagador = 10;
        BigDecimal valorPorTransferencia = new BigDecimal("10.00");
        int totalTransferencias = payerIds.size() * transferenciasPorPagador;

        ExecutorService executor = Executors.newFixedThreadPool(10);
        AtomicInteger sucessos = new AtomicInteger();
        AtomicInteger falhas = new AtomicInteger();

        // Dispara todas as transferências "ao mesmo tempo" contra a hotAccount,
        // de pagadores diferentes — o cenário que mais expõe deadlocks e
        // condições de corrida em contas de alto volume.
        for (UUID payerId : payerIds) {
            for (int i = 0; i < transferenciasPorPagador; i++) {
                // Coluna end_to_end_id é VARCHAR(35) (imita o padrão real do
                // Pix, que usa 32 caracteres fixos) — por isso usamos só os
                // 8 primeiros caracteres do UUID do pagador, não o UUID
                // inteiro (36 chars), que estourava o limite e fazia TODA
                // inserção falhar silenciosamente.
                String endToEndId = "CONC-" + payerId.toString().substring(0, 8) + "-" + i;
                executor.submit(() -> {
                    try {
                        transferService.transfer(endToEndId, payerId, hotAccountId, valorPorTransferencia);
                        sucessos.incrementAndGet();
                    } catch (Exception e) {
                        falhas.incrementAndGet();
                    }
                });
            }
        }

        executor.shutdown();
        boolean finalizou = executor.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(finalizou).as("todas as threads devem terminar dentro do tempo limite").isTrue();

        // Todas as transferências devem ter sucesso (saldo dos pagadores é
        // suficiente) — nenhuma deve falhar por erro de concorrência não tratado
        assertThat(sucessos.get()).isEqualTo(totalTransferencias);
        assertThat(falhas.get()).isZero();

        // A prova real de que não há condição de corrida: o saldo final da
        // hotAccount deve ser EXATAMENTE a soma de todas as transferências,
        // nem um centavo a mais ou a menos.
        Account hotAccountAtualizada = accountRepository.findById(hotAccountId).orElseThrow();
        BigDecimal esperado = valorPorTransferencia.multiply(BigDecimal.valueOf(totalTransferencias));
        assertThat(hotAccountAtualizada.getBalance()).isEqualByComparingTo(esperado);

        // Cada pagador deve ter debitado exatamente o valor correto
        for (UUID payerId : payerIds) {
            Account payer = accountRepository.findById(payerId).orElseThrow();
            BigDecimal esperadoPagador = new BigDecimal("1000.00")
                    .subtract(valorPorTransferencia.multiply(BigDecimal.valueOf(transferenciasPorPagador)));
            assertThat(payer.getBalance()).isEqualByComparingTo(esperadoPagador);
        }
    }

    @Test
    void deveGarantirIdempotenciaSobRequisicoesConcorrentesComMesmoEndToEndId() throws InterruptedException {
        // Simula o cenário real de um cliente HTTP que reenvia a mesma
        // requisição (timeout de rede) enquanto a primeira ainda está
        // sendo processada — a idempotência deve impedir dupla transferência
        // mesmo com as chamadas chegando quase simultaneamente.
        String endToEndId = "DUPLICADO-001";
        UUID payerId = payerIds.get(0);
        int tentativasSimultaneas = 8;

        ExecutorService executor = Executors.newFixedThreadPool(tentativasSimultaneas);
        AtomicInteger sucessos = new AtomicInteger();

        for (int i = 0; i < tentativasSimultaneas; i++) {
            executor.submit(() -> {
                try {
                    transferService.transfer(endToEndId, payerId, hotAccountId, new BigDecimal("50.00"));
                    sucessos.incrementAndGet();
                } catch (Exception ignored) {
                    // pode haver falha de unique constraint em corrida bem apertada — aceitável
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(15, TimeUnit.SECONDS);

        // O ponto central do teste: independentemente de quantas tentativas
        // "vencerem" a corrida, o débito na conta pagadora deve ter
        // acontecido no máximo UMA vez.
        Account payer = accountRepository.findById(payerId).orElseThrow();
        assertThat(payer.getBalance()).isEqualByComparingTo("950.00"); // 1000 - 50, uma única vez
    }
}
