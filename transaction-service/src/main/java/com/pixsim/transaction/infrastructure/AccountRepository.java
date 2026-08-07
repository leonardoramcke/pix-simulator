package com.pixsim.transaction.infrastructure;

import com.pixsim.transaction.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Alternativa de lock pessimista, disponível para fluxos que
     * exigem consistência forte imediata (ex: reconciliação em lote),
     * onde o custo de bloqueio é aceitável e a contenção é baixa.
     * Não é o caminho padrão do fluxo de transferência (ver otimista
     * no PixTransferService).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Account findByIdForUpdate(UUID id);
}
