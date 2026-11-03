package com.izalco.transferencias.persistencia;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TransferenciaRepositorio extends JpaRepository<TransferenciaEntidad, UUID> {
}
