package com.remittance.optimizer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RemittanceRequest {
    
    private BigDecimal amount;
    private String sourceCountry;
    private String sourceCurrency;
    private String destinationCountry = "Pakistan";
    private String destinationCurrency = "PKR";
    private Priority priority = Priority.CHEAPEST;
}
