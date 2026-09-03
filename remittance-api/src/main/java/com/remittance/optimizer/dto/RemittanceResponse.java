package com.remittance.optimizer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemittanceResponse {
    
    private RemittanceRequest originalRequest;
    private List<RemittanceResult> results;
    private RemittanceResult bestChannel;
    private String recommendation;
}
