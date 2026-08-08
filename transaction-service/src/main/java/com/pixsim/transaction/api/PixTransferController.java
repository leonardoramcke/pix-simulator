package com.pixsim.transaction.api;

import com.pixsim.transaction.application.PixTransferService;
import com.pixsim.transaction.domain.InsufficientFundsException;
import com.pixsim.transaction.domain.PixTransaction;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pix")
public class PixTransferController {

    private static final Logger log = LoggerFactory.getLogger(PixTransferController.class);

    private final PixTransferService transferService;

    public PixTransferController(PixTransferService transferService) {
        this.transferService = transferService;
    }

    public record TransferRequest(
            @NotBlank String endToEndId,
            @NotNull UUID sourceAccountId,
            @NotNull UUID targetAccountId,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount
    ) {}

    public record TransferResponse(UUID transactionId, String status) {}

    @PostMapping("/transfer")
    @RateLimiter(name = "pixTransfer")               // limita throughput por instância
    @CircuitBreaker(name = "pixTransfer", fallbackMethod = "transferFallback")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        PixTransaction tx = transferService.transfer(
                request.endToEndId(), request.sourceAccountId(),
                request.targetAccountId(), request.amount());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TransferResponse(tx.getId(), tx.getStatus().name()));
    }

    // Fallback do Circuit Breaker: acionado para QUALQUER exceção lançada
    // pelo método protegido — inclusive as de negócio, mesmo estando em
    // ignore-exceptions no application.yml (isso só afeta a contagem de
    // falhas do circuito, não impede o fallback de disparar). Por isso,
    // erros de negócio são relançados aqui para o GlobalExceptionHandler
    // responder com o status HTTP correto — só problemas de
    // infraestrutura real caem na resposta genérica abaixo.
    private ResponseEntity<TransferResponse> transferFallback(TransferRequest request, Throwable t) {
        if (t instanceof InsufficientFundsException || t instanceof IllegalArgumentException) {
            throw (RuntimeException) t;
        }
        log.error("Fallback acionado para endToEndId={}: {}", request.endToEndId(), t.toString(), t);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new TransferResponse(null, "TEMPORARILY_UNAVAILABLE"));
    }
}
