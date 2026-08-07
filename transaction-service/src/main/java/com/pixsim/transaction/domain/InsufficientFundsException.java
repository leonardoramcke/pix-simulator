package com.pixsim.transaction.domain;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(UUID accountId, BigDecimal requested, BigDecimal available) {
        super("Saldo insuficiente na conta %s: solicitado=%s, disponível=%s"
                .formatted(accountId, requested, available));
    }
}
