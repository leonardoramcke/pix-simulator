package com.pixsim.account.domain;

public class DuplicatePixKeyException extends RuntimeException {
    public DuplicatePixKeyException(String pixKey) {
        super("Já existe uma conta com a chave Pix: " + pixKey);
    }
}
