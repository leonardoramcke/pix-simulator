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
}
