-- Contas de teste para validação manual do endpoint /transfer.
-- IDs fixos para facilitar os testes via curl/Postman.
INSERT INTO accounts (id, pix_key, balance, version) VALUES
    ('11111111-1111-1111-1111-111111111111', 'joao@email.com', 1000.00, 0),
    ('22222222-2222-2222-2222-222222222222', 'maria@email.com', 500.00, 0);
