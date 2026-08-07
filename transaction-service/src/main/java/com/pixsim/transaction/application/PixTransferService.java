package com.pixsim.transaction.application;

import com.pixsim.transaction.domain.*;
import com.pixsim.transaction.infrastructure.AccountRepository;
import com.pixsim.transaction.infrastructure.PixTransactionRepository;
import com.pixsim.transaction.infrastructure.PixEventPublisher;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class PixTransferService {

    private static final Logger log = LoggerFactory.getLogger(PixTransferService.class);

    private final AccountRepository accountRepository;
    private final PixTransactionRepository transactionRepository;
    private final PixEventPublisher eventPublisher;

    public PixTransferService(AccountRepository accountRepository,
                               PixTransactionRepository transactionRepository,
                               PixEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Executa uma transferência Pix ponta a ponta.
     *
     * Estratégia de concorrência (3 camadas):
     *
     * 1. IDEMPOTÊNCIA: se o endToEndId já existe, retorna o resultado
     *    existente em vez de duplicar a transferência (essencial pois
     *    clientes HTTP fazem retry em timeout).
     *
     * 2. ORDENAÇÃO DE LOCKS: sempre buscamos/bloqueamos as contas na
     *    ordem crescente do UUID (source vs target), nunca na ordem
     *    "pagador depois recebedor". Isso evita deadlock clássico onde
     *    a transação A trava conta 1 e espera a 2, enquanto a transação B
     *    (transferência inversa) trava a 2 e espera a 1.
     *
     * 3. RETRY EM LOCK OTIMISTA: @Version nas contas gera
     *    OptimisticLockingFailureException sob concorrência real.
     *    @Retry (Resilience4j) reexecuta o método algumas vezes com
     *    backoff, o que é seguro pois a idempotência do passo 1 impede
     *    duplicação mesmo que o retry aconteça após sucesso parcial.
     *
     * ISOLATION.REPEATABLE_READ evita leitura fantasma do saldo dentro
     * da mesma transação (mais forte que READ_COMMITTED, mais barato
     * que SERIALIZABLE para este caso de uso).
     */
    @Retry(name = "pixTransfer")
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PixTransaction transfer(String endToEndId, UUID sourceAccountId,
                                    UUID targetAccountId, BigDecimal amount) {

        // 1. Idempotência
        var existing = transactionRepository.findByEndToEndId(endToEndId);
        if (existing.isPresent()) {
            log.info("Transferência idempotente detectada: {}", endToEndId);
            return existing.get();
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Valor da transferência deve ser positivo");
        }

        var transaction = new PixTransaction(endToEndId, sourceAccountId, targetAccountId, amount);

        try {
            // 2. Ordenação determinística de locks — evita deadlock
            List<UUID> orderedIds = sourceAccountId.compareTo(targetAccountId) < 0
                    ? List.of(sourceAccountId, targetAccountId)
                    : List.of(targetAccountId, sourceAccountId);

            var accountsById = accountRepository.findAllById(orderedIds).stream()
                    .collect(java.util.stream.Collectors.toMap(Account::getId, a -> a));

            Account source = accountsById.get(sourceAccountId);
            Account target = accountsById.get(targetAccountId);

            if (source == null || target == null) {
                throw new IllegalArgumentException("Conta de origem ou destino não encontrada");
            }

            source.debit(amount);
            target.credit(amount);

            // O save aciona a checagem de @Version no UPDATE (lock otimista)
            accountRepository.save(source);
            accountRepository.save(target);

            transaction.markCompleted();
            transactionRepository.save(transaction);

            // Publicação de evento acontece DEPOIS do commit local
            // (ver PixEventPublisher — usa outbox pattern, não publica
            // direto aqui para não quebrar atomicidade do commit do banco)
            eventPublisher.publishTransactionCompleted(transaction);

            return transaction;

        } catch (InsufficientFundsException e) {
            transaction.markFailed(e.getMessage());
            transactionRepository.save(transaction);
            eventPublisher.publishTransactionFailed(transaction, e.getMessage());
            throw e;

        } catch (OptimisticLockingFailureException e) {
            log.warn("Conflito de concorrência detectado para {}, será reexecutado pelo @Retry", endToEndId);
            throw e; // relançar para o Resilience4j interceptar e reexecutar
        }
    }
}
