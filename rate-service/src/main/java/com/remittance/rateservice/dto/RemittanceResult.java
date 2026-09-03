package com.remittance.rateservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemittanceResult {

    private String channelName;
    private BigDecimal exchangeRate;
    private BigDecimal feeAmount;
    private FeeType feeType;
    private BigDecimal amountReceived;
    private String estimatedSpeed;
}
