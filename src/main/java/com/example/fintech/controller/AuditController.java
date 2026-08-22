package com.example.fintech.controller;

import com.example.fintech.service.ChainVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final ChainVerificationService chainVerificationService;

    @GetMapping("/verify-chain")
    public ResponseEntity<ChainVerificationService.VerificationResult> verifyChain() {
        return ResponseEntity.ok(chainVerificationService.verifyChain());
    }
}
