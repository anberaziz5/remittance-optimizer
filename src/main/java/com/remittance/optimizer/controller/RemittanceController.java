package com.remittance.optimizer.controller;

import com.remittance.optimizer.dto.RemittanceRequest;
import com.remittance.optimizer.dto.RemittanceResponse;
import com.remittance.optimizer.service.ComparisonService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/remittance")
public class RemittanceController {

    private final ComparisonService comparisonService;

    public RemittanceController(ComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    @PostMapping("/compare")
    public ResponseEntity<RemittanceResponse> compare(@RequestBody RemittanceRequest request) {
        RemittanceResponse response = comparisonService.compare(request);
        return ResponseEntity.ok(response);
    }
}
