package com.baleshop.baleshop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BaleshopApplication {

	public static void main(String[] args) {
		System.setProperty("java.net.preferIPv4Stack",
				System.getProperty("java.net.preferIPv4Stack", "true"));
		System.setProperty("java.net.preferIPv6Addresses",
				System.getProperty("java.net.preferIPv6Addresses", "false"));
		SpringApplication.run(BaleshopApplication.class, args);
	}

}
