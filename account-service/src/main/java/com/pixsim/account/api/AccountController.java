package com.pixsim.account.api;

import com.pixsim.account.application.AccountService;
import com.pixsim.account.domain.Account;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    public record CreateAccountRequest(
            @NotBlank String pixKey,
            @NotNull @DecimalMin(value = "0.00") BigDecimal initialBalance
    ) {}

    public record AccountResponse(UUID id, String pixKey, BigDecimal balance) {
        static AccountResponse from(Account account) {
            return new AccountResponse(account.getId(), account.getPixKey(), account.getBalance());
        }
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(request.pixKey(), request.initialBalance());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> get(@PathVariable UUID id) {
        Account account = accountService.getAccount(id);
        return ResponseEntity.ok(AccountResponse.from(account));
    }

    @GetMapping
    public ResponseEntity<java.util.List<AccountResponse>> list() {
        var accounts = accountService.listAccounts().stream()
                .map(AccountResponse::from)
                .toList();
        return ResponseEntity.ok(accounts);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        accountService.deleteAccount(id);
        return ResponseEntity.noContent().build();
    }
}
