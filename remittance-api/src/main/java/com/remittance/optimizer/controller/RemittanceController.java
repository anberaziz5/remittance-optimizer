package com.remittance.optimizer.controller;

import com.remittance.optimizer.dto.RemittanceRequest;
import com.remittance.optimizer.dto.RemittanceResponse;
import com.remittance.optimizer.service.RemittanceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/remittance")
public class RemittanceController {

    private final RemittanceService remittanceService;

    public RemittanceController(RemittanceService remittanceService) {
        this.remittanceService = remittanceService;
    }

    @PostMapping("/compare")
    public ResponseEntity<RemittanceResponse> compare(@Valid @RequestBody RemittanceRequest request) {
        RemittanceResponse response = remittanceService.compare(request);
        return ResponseEntity.ok(response);
    }
}
