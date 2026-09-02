package com.pixsim.account.application;

import com.pixsim.account.domain.Account;
import com.pixsim.account.domain.AccountNotFoundException;
import com.pixsim.account.domain.DuplicatePixKeyException;
import com.pixsim.account.infrastructure.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public Account createAccount(String pixKey, BigDecimal initialBalance) {
        if (accountRepository.existsByPixKey(pixKey)) {
            throw new DuplicatePixKeyException(pixKey);
        }
        Account account = new Account(pixKey, initialBalance);
        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public java.util.List<Account> listAccounts() {
        return accountRepository.findAll();
    }

    @Transactional
    public void deleteAccount(UUID id) {
        if (!accountRepository.existsById(id)) {
            throw new AccountNotFoundException(id);
        }
        // Se a conta já participou de alguma transferência, o Postgres
        // rejeita a exclusão por causa da chave estrangeira em
        // pix_transactions (source_account_id / target_account_id) —
        // isso é intencional: preserva o histórico de transações mesmo
        // que a conta seja "encerrada", como um banco real faria.
        // A exceção resultante (DataIntegrityViolationException) é
        // traduzida pelo GlobalExceptionHandler numa resposta amigável.
        accountRepository.deleteById(id);
    }
}
