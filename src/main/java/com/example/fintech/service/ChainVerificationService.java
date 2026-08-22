package com.example.fintech.service;

import com.example.fintech.model.LedgerEntry;
import com.example.fintech.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChainVerificationService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final HashChainService hashChainService;

    public record VerificationResult(boolean valid, long entriesChecked, Long firstBrokenEntryId, String detail) {}

    /**
     * Replays every ledger_entries row in insertion order and recomputes
     * each hash from its own data + the previous row's hash, comparing
     * against what's stored. Detects tampering that happened outside the
     * application entirely (e.g. a direct DB edit bypassing the
     * append-only trigger via a superuser role, or a restore from a
     * doctored backup) - the actual audit tool you'd run to prove nothing
     * has been altered since it was written.
     */
    public VerificationResult verifyChain() {
        List<LedgerEntry> entries = ledgerEntryRepository.findAllByOrderByIdAsc();
        String expectedPrevHash = "0".repeat(64);

        for (LedgerEntry entry : entries) {
            if (!expectedPrevHash.equals(entry.getPrevHash())) {
                return new VerificationResult(false, entries.size(), entry.getId(),
                        "prev_hash mismatch at entry " + entry.getId());
            }
            String recomputed = hashChainService.computeHash(
                    entry.getPrevHash(), entry.getTransaction().getId(),
                    entry.getAccount().getAccountRef(), entry.getDirection(),
                    entry.getAmount(), entry.getCreatedAt());

            if (!recomputed.equals(entry.getEntryHash())) {
                return new VerificationResult(false, entries.size(), entry.getId(),
                        "entry_hash mismatch at entry " + entry.getId() + " - row data does not match its recorded hash");
            }
            expectedPrevHash = entry.getEntryHash();
        }

        return new VerificationResult(true, entries.size(), null, "chain intact");
    }
}
