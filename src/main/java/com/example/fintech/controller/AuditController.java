package com.example.fintech.controller;

import com.example.fintech.service.ChainVerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
@Slf4j
public class AuditController {

    private final ChainVerificationService chainVerificationService;

    @GetMapping("/verify-chain")
    public ResponseEntity<ChainVerificationService.VerificationResult> verifyChain() {
        ChainVerificationService.VerificationResult result = chainVerificationService.verifyChain();
        // A broken chain means the ledger was tampered with outside the app
        // (direct DB edit, doctored backup) - this is a security incident,
        // not a routine 4xx, so it's always logged at ERROR regardless of
        // who called this endpoint or why.
        if (!result.valid()) {
            log.error("HASH CHAIN VERIFICATION FAILED: {} (entries checked: {}, first broken entry: {})",
                    result.detail(), result.entriesChecked(), result.firstBrokenEntryId());
        } else {
            log.info("Hash chain verified intact ({} entries checked)", result.entriesChecked());
        }
        return ResponseEntity.ok(result);
    }
}
