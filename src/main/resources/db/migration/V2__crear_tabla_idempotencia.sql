-- Llaves de idempotencia (header X-Idempotency-Key).
-- La PRIMARY KEY sobre "llave" es lo que garantiza que dos solicitudes con la misma llave,
-- aunque lleguen al mismo tiempo a réplicas distintas, no puedan procesarse las dos.
CREATE TABLE idempotencia (
    llave            UUID         PRIMARY KEY,
    -- SHA-256 (hex) del contenido de la solicitud: detecta la misma llave usada con otro cuerpo.
    hash_solicitud   VARCHAR(64)  NOT NULL,
    transferencia_id UUID         NOT NULL REFERENCES transferencia (id),
    estado           VARCHAR(20)  NOT NULL CHECK (estado IN ('EN_PROCESO', 'COMPLETADA')),
    -- Respuesta guardada para devolverla idéntica en los reintentos (replay).
    http_status      INTEGER,
    respuesta        TEXT,
    creado_en        TIMESTAMPTZ  NOT NULL,
    expira_en        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_idempotencia_completada
        CHECK (estado = 'EN_PROCESO' OR (http_status IS NOT NULL AND respuesta IS NOT NULL))
);

-- Para purgar llaves vencidas.
CREATE INDEX ix_idempotencia_expira_en ON idempotencia (expira_en);
