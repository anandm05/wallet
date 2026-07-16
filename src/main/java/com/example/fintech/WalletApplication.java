package com.example.fintech;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.example")
public class WalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletApplication.class, args);
    }

}
