package com.pixsim.account.api;

import com.pixsim.account.domain.AccountNotFoundException;
import com.pixsim.account.domain.DuplicatePixKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorResponse(String error, String message) {}

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(AccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("ACCOUNT_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(DuplicatePixKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicatePixKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATE_PIX_KEY", e.getMessage()));
    }

    // Postgres rejeita a exclusão de uma conta que já participou de
    // transferências (chave estrangeira em pix_transactions) — traduz
    // isso numa mensagem clara em vez do erro genérico de banco.
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrityViolation(org.springframework.dao.DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("ACCOUNT_HAS_TRANSACTIONS",
                        "Não é possível excluir: esta conta já participou de transferências Pix."));
    }
}
