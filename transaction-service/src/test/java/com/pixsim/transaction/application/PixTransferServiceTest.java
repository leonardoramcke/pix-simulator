package com.pixsim.transaction.application;

import com.pixsim.transaction.domain.Account;
import com.pixsim.transaction.domain.InsufficientFundsException;
import com.pixsim.transaction.domain.PixTransaction;
import com.pixsim.transaction.infrastructure.AccountRepository;
import com.pixsim.transaction.infrastructure.PixEventPublisher;
import com.pixsim.transaction.infrastructure.PixTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Testes unitários: validam as REGRAS DE NEGÓCIO da transferência de forma
 * isolada, sem tocar banco ou Kafka de verdade (tudo mockado). Rápidos
 * (rodam em milissegundos) — ideais para rodar a cada save durante o
 * desenvolvimento.
 *
 * O comportamento sob concorrência real (múltiplas threads disputando a
 * mesma conta) é validado separadamente no teste de integração, pois
 * exige um banco de verdade para gerar OptimisticLockingFailureException.
 */
@ExtendWith(MockitoExtension.class)
class PixTransferServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PixTransactionRepository transactionRepository;

    @Mock
    private PixEventPublisher eventPublisher;

    @InjectMocks
    private PixTransferService transferService;

    private UUID sourceId;
    private UUID targetId;
    private Account sourceAccount;
    private Account targetAccount;

    @BeforeEach
    void setUp() {
        sourceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        targetId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        sourceAccount = new Account("joao@email.com", new BigDecimal("1000.00"));
        targetAccount = new Account("maria@email.com", new BigDecimal("500.00"));
        setAccountId(sourceAccount, sourceId);
        setAccountId(targetAccount, targetId);
    }

    // Account.id é gerado pelo banco (@GeneratedValue); em teste unitário
    // sem persistência real, precisamos setar via reflection para simular
    // uma entidade já existente.
    private void setAccountId(Account account, UUID id) {
        try {
            var field = Account.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(account, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void deveTransferirComSucessoQuandoSaldoSuficiente() {
        when(transactionRepository.findByEndToEndId("E123")).thenReturn(Optional.empty());
        when(accountRepository.findAllById(any())).thenReturn(List.of(sourceAccount, targetAccount));
        when(transactionRepository.save(any(PixTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        PixTransaction result = transferService.transfer("E123", sourceId, targetId, new BigDecimal("100.00"));

        assertThat(result.getStatus()).isEqualTo(PixTransaction.Status.COMPLETED);
        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("900.00");
        assertThat(targetAccount.getBalance()).isEqualByComparingTo("600.00");
        verify(eventPublisher).publishTransactionCompleted(any());
    }

    @Test
    void deveLancarExcecaoQuandoSaldoInsuficiente() {
        when(transactionRepository.findByEndToEndId("E456")).thenReturn(Optional.empty());
        when(accountRepository.findAllById(any())).thenReturn(List.of(sourceAccount, targetAccount));
        when(transactionRepository.save(any(PixTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() ->
                transferService.transfer("E456", sourceId, targetId, new BigDecimal("999999.00"))
        ).isInstanceOf(InsufficientFundsException.class);

        // saldo não deve ter sido alterado em nenhuma das contas
        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(targetAccount.getBalance()).isEqualByComparingTo("500.00");
        verify(eventPublisher).publishTransactionFailed(any(), anyString());
        verify(eventPublisher, never()).publishTransactionCompleted(any());
    }

    @Test
    void deveSerIdempotenteParaEndToEndIdJaProcessado() {
        PixTransaction transacaoExistente = new PixTransaction(
                "E789", sourceId, targetId, new BigDecimal("100.00"));
        transacaoExistente.markCompleted();

        when(transactionRepository.findByEndToEndId("E789")).thenReturn(Optional.of(transacaoExistente));

        PixTransaction result = transferService.transfer("E789", sourceId, targetId, new BigDecimal("100.00"));

        assertThat(result).isSameAs(transacaoExistente);
        // nenhuma conta deve ser tocada — a transferência não é reexecutada
        verify(accountRepository, never()).findAllById(any());
        verify(eventPublisher, never()).publishTransactionCompleted(any());
    }

    @Test
    void deveRejeitarValorZeroOuNegativo() {
        when(transactionRepository.findByEndToEndId("E999")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                transferService.transfer("E999", sourceId, targetId, BigDecimal.ZERO)
        ).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(accountRepository);
    }

    @Test
    void deveLancarExcecaoQuandoContaNaoEncontrada() {
        when(transactionRepository.findByEndToEndId("E111")).thenReturn(Optional.empty());
        when(accountRepository.findAllById(any())).thenReturn(List.of(sourceAccount)); // só uma conta retornada

        assertThatThrownBy(() ->
                transferService.transfer("E111", sourceId, targetId, new BigDecimal("50.00"))
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("não encontrada");
    }
}
