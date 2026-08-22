package com.example.fintech.dto;

import com.example.fintech.model.Direction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

// Deliberately separate from the JPA entities so the API contract can't be
// used to set ids/hashes/timestamps directly from the client.
@Getter @Setter
public class TransferRequest {

    private String description;

    @NotNull
    @Size(min = 2, message = "A transaction needs at least two legs to be balanced (one debit, one credit)")
    @Valid
    private List<Leg> entries;

    @Getter @Setter
    public static class Leg {
        @NotBlank private String accountRef;
        @NotNull private Direction direction;
        @NotNull @DecimalMin(value = "0.01", message = "amount must be positive") private BigDecimal amount;
    }
}
