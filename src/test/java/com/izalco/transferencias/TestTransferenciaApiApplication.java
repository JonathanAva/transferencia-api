package com.izalco.transferencias;

import org.springframework.boot.SpringApplication;

public class TestTransferenciaApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(TransferenciaApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
