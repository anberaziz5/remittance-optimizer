package com.remittance.rateservice.controller;

import com.remittance.rateservice.dto.RemittanceRequest;
import com.remittance.rateservice.dto.RemittanceResult;
import com.remittance.rateservice.service.ComparisonService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rates")
public class RateController {

    private final ComparisonService comparisonService;

    public RateController(ComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    @PostMapping("/compare")
    public ResponseEntity<List<RemittanceResult>> compare(@Valid @RequestBody RemittanceRequest request) {
        List<RemittanceResult> results = comparisonService.compare(request);
        return ResponseEntity.ok(results);
    }
}
