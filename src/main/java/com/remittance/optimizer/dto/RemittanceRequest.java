package com.remittance.optimizer.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RemittanceRequest {

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    @Digits(integer = 12, fraction = 2, message = "Amount must have at most 12 integer digits and 2 decimal places")
    private BigDecimal amount;

    @NotNull(message = "Source country is required")
    @NotBlank(message = "Source country cannot be empty")
    private String sourceCountry;

    @NotNull(message = "Source currency is required")
    @NotBlank(message = "Source currency cannot be empty")
    private String sourceCurrency;

    @NotNull(message = "Destination country is required")
    @NotBlank(message = "Destination country cannot be empty")
    private String destinationCountry = "Pakistan";

    @NotNull(message = "Destination currency is required")
    @NotBlank(message = "Destination currency cannot be empty")
    private String destinationCurrency = "PKR";

    @NotNull(message = "Priority is required")
    private Priority priority = Priority.CHEAPEST;
}
