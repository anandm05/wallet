package com.example.fintech.service;

import com.example.fintech.dto.TransactionEvent;
import com.example.fintech.dto.TransferRequest;
import com.example.fintech.dto.TransferResponse;
import com.example.fintech.model.Account;
import com.example.fintech.model.Direction;
import com.example.fintech.model.LedgerEntry;
import com.example.fintech.model.LedgerTransaction;
import com.example.fintech.model.OutboxEvent;
import com.example.fintech.repository.AccountRepository;
import com.example.fintech.repository.LedgerEntryRepository;
import com.example.fintech.repository.LedgerTransactionRepository;
import com.example.fintech.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransferService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final HashChainService hashChainService;
    private final ObjectMapper objectMapper;

    /**
     * Posts a balanced, multi-leg double-entry transaction.
     *
     * Idempotency relies solely on the DB unique constraint on
     * ledger_transactions.idempotency_key, caught by the controller as
     * DataIntegrityViolationException - deliberately NOT a
     * findByIdempotencyKey(...).ifPresent(...) check performed first. A
     * separate check-then-insert is a race: two concurrent requests with
     * the same key could both pass the check before either commits. The
     * unique constraint IS the check, and it's atomic by construction.
     */
    @Transactional
    public TransferResponse postTransaction(TransferRequest request, String idempotencyKey) {
        validateBalanced(request);

        LedgerTransaction transaction = new LedgerTransaction();
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setDescription(request.getDescription());
        transaction.setCreatedAt(LocalDateTime.now());
        transaction = transactionRepository.save(transaction);
        // Flush now so a duplicate idempotency key fails fast, inside this
        // method, with the full stack trace still attached to this request -
        // rather than surfacing later at an unrelated flush point.
        transactionRepository.flush();

        List<String> orderedRefs = request.getEntries().stream()
                .map(TransferRequest.Leg::getAccountRef)
                .distinct()
                .sorted()
                .toList();
        List<Account> locked = accountRepository.lockByAccountRefs(orderedRefs);
        if (locked.size() != orderedRefs.size()) {
            throw new NoSuchElementException("One or more accounts do not exist: " + orderedRefs);
        }
        Map<String, Account> accountsByRef = new HashMap<>();
        for (Account a : locked) {
            accountsByRef.put(a.getAccountRef(), a);
        }

        String runningHash = hashChainService.lockAndGetLastHash();
        List<LedgerEntry> savedEntries = new ArrayList<>();

        for (TransferRequest.Leg leg : request.getEntries()) {
            Account account = accountsByRef.get(leg.getAccountRef());
            applyToBalance(account, leg.getDirection(), leg.getAmount());

            // Truncated to microseconds because Postgres' TIMESTAMP column
            // only stores that precision - hashing the untruncated
            // nanosecond-precision clock value would make the hash
            // unreproducible from what's actually persisted, breaking
            // every future verify-chain check against real data.
            LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            String hash = hashChainService.computeHash(
                    runningHash, transaction.getId(), account.getAccountRef(),
                    leg.getDirection(), leg.getAmount(), now);

            LedgerEntry entry = new LedgerEntry();
            entry.setTransaction(transaction);
            entry.setAccount(account);
            entry.setDirection(leg.getDirection());
            entry.setAmount(leg.getAmount());
            entry.setCreatedAt(now);
            entry.setPrevHash(runningHash);
            entry.setEntryHash(hash);

            savedEntries.add(ledgerEntryRepository.save(entry));
            runningHash = hash;
        }
        hashChainService.advanceChain(runningHash);

        writeOutboxEvent(transaction, savedEntries);

        return toResponse(transaction, savedEntries);
    }

    private void validateBalanced(TransferRequest request) {
        BigDecimal debits = sumByDirection(request, Direction.DEBIT);
        BigDecimal credits = sumByDirection(request, Direction.CREDIT);
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("Unbalanced transaction: debits=" + debits + " credits=" + credits);
        }
    }

    private BigDecimal sumByDirection(TransferRequest request, Direction direction) {
        return request.getEntries().stream()
                .filter(e -> e.getDirection() == direction)
                .map(TransferRequest.Leg::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void applyToBalance(Account account, Direction direction, BigDecimal amount) {
        BigDecimal delta = direction == Direction.CREDIT ? amount : amount.negate();
        BigDecimal updated = account.getBalance().add(delta);
        if (updated.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Insufficient funds in account " + account.getAccountRef());
        }
        account.setBalance(updated);
        accountRepository.save(account);
    }

    /**
     * Writes the event to the SAME DB transaction as the ledger entries -
     * this is the transactional outbox pattern. A separate scheduled
     * OutboxPublisher (poll interval configurable) reads unpublished rows
     * and sends them to Kafka afterward, with retry/backoff. This closes
     * the dual-write gap: the ledger write and the "there is an event to
     * publish" fact commit or roll back together, atomically. Only the
     * actual Kafka delivery happens outside this transaction, and that's
     * fine - the outbox row is durable proof the event still needs sending
     * even if the broker is unreachable right now.
     */
    private void writeOutboxEvent(LedgerTransaction transaction, List<LedgerEntry> entries) {
        TransactionEvent event = new TransactionEvent(
                transaction.getId(),
                entries.stream()
                        .map(e -> new TransactionEvent.Leg(e.getAccount().getAccountRef(), e.getAmount(), e.getDirection().name()))
                        .toList());

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setAggregateType("LEDGER_TRANSACTION");
        outboxEvent.setAggregateId(transaction.getId().toString());
        outboxEvent.setEventType("TRANSACTION_POSTED");
        outboxEvent.setPayload(objectMapper.valueToTree(event));
        outboxEvent.setCreatedAt(LocalDateTime.now());
        outboxEvent.setNextAttemptAt(LocalDateTime.now());
        outboxEventRepository.save(outboxEvent);
    }

    private TransferResponse toResponse(LedgerTransaction transaction, List<LedgerEntry> entries) {
        List<TransferResponse.EntryView> views = entries.stream()
                .map(e -> TransferResponse.EntryView.builder()
                        .entryId(e.getId())
                        .accountRef(e.getAccount().getAccountRef())
                        .direction(e.getDirection())
                        .amount(e.getAmount())
                        .entryHash(e.getEntryHash())
                        .build())
                .toList();

        return TransferResponse.builder()
                .transactionId(transaction.getId())
                .idempotencyKey(transaction.getIdempotencyKey())
                .description(transaction.getDescription())
                .createdAt(transaction.getCreatedAt())
                .entries(views)
                .build();
    }
}
