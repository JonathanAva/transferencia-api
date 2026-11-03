-- Transferencias registradas, en cualquier estado (aprobadas, rechazadas y pendientes de confirmación).
CREATE TABLE transferencia (
    id                        UUID          PRIMARY KEY,
    cuenta_origen             VARCHAR(20)   NOT NULL,
    cuenta_destino            VARCHAR(20)   NOT NULL,
    banco_destino             VARCHAR(8)    NOT NULL,
    -- NUMERIC exacto (nunca float): 13 enteros y 2 decimales, igual que la validación de la API.
    monto                     NUMERIC(15, 2) NOT NULL CHECK (monto > 0),
    moneda                    VARCHAR(3)    NOT NULL,
    concepto                  VARCHAR(140)  NOT NULL,
    -- TIMESTAMPTZ guarda el instante exacto (en UTC). El offset original se guarda aparte
    -- para devolver la fecha tal como la envió la app (por ejemplo, -06:00).
    fecha_solicitud           TIMESTAMPTZ   NOT NULL,
    offset_solicitud_segundos INTEGER       NOT NULL,
    estado                    VARCHAR(30)   NOT NULL
        CHECK (estado IN ('APROBADA', 'RECHAZADA', 'PENDIENTE_CONFIRMACION')),
    referencia_core           VARCHAR(64),
    mensaje                   VARCHAR(255)  NOT NULL,
    fecha_registro            TIMESTAMPTZ   NOT NULL,
    fecha_actualizacion       TIMESTAMPTZ   NOT NULL
);

-- Índice parcial: un futuro proceso de conciliación buscará solo las pendientes.
CREATE INDEX ix_transferencia_pendientes ON transferencia (fecha_registro)
    WHERE estado = 'PENDIENTE_CONFIRMACION';
