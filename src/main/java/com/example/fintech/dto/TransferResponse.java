package com.example.fintech.dto;

import com.example.fintech.model.Direction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter @Builder @AllArgsConstructor
public class TransferResponse {
    private Long transactionId;
    private String idempotencyKey;
    private String description;
    private LocalDateTime createdAt;
    private List<EntryView> entries;

    @Getter @Builder @AllArgsConstructor
    public static class EntryView {
        private Long entryId;
        private String accountRef;
        private Direction direction;
        private BigDecimal amount;
        private String entryHash;
    }
}
