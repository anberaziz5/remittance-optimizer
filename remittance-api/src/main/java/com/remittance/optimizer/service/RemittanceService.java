package com.remittance.optimizer.service;

import com.remittance.optimizer.dto.Priority;
import com.remittance.optimizer.dto.RemittanceRequest;
import com.remittance.optimizer.dto.RemittanceResponse;
import com.remittance.optimizer.dto.RemittanceResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

@Service
public class RemittanceService {

    private final RestTemplate restTemplate;
    private final String rateServiceUrl;

    public RemittanceService(RestTemplate restTemplate,
                             @Value("${rates.service.url}") String rateServiceUrl) {
        this.restTemplate = restTemplate;
        this.rateServiceUrl = rateServiceUrl;
    }

    public RemittanceResponse compare(RemittanceRequest request) {
        List<RemittanceResult> results = fetchRankedResults(request);

        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("Rate service returned no comparison results");
        }

        RemittanceResult bestChannel = results.get(0);
        RemittanceResult worstChannel = results.get(results.size() - 1);
        String recommendation = buildRecommendation(bestChannel, worstChannel, request.getAmount(), request.getPriority());

        return RemittanceResponse.builder()
                .originalRequest(request)
                .results(results)
                .bestChannel(bestChannel)
                .recommendation(recommendation)
                .build();
    }

    private List<RemittanceResult> fetchRankedResults(RemittanceRequest request) {
        try {
            ResponseEntity<RemittanceResult[]> response = restTemplate.postForEntity(
                    rateServiceUrl, request, RemittanceResult[].class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("Rate service returned an unsuccessful response");
            }
            return Arrays.asList(response.getBody());
        } catch (HttpStatusCodeException ex) {
            String message = extractErrorMessage(ex);
            throw new IllegalArgumentException(message, ex);
        } catch (ResourceAccessException ex) {
            throw new IllegalStateException("Rate service is currently unavailable. Please try again later.", ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Failed to communicate with rate service: " + ex.getMessage(), ex);
        }
    }

    private String extractErrorMessage(HttpStatusCodeException ex) {
        String body = ex.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return ex.getStatusCode().toString();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<?, ?> map = mapper.readValue(body, java.util.Map.class);
            Object error = map.get("error");
            if (error != null) {
                return error.toString();
            }
        } catch (Exception ignored) {
            // Fall back to raw body if JSON parsing fails
        }
        return body;
    }

    private String buildRecommendation(RemittanceResult best, RemittanceResult worst,
                                       BigDecimal amount, Priority priority) {
        return switch (priority) {
            case CHEAPEST -> {
                BigDecimal savings = computeSavings(best, worst);
                yield String.format(
                        "%s is the best option for sending $%s — you'll receive %s PKR, which is %s PKR more than %s (the most expensive option).",
                        best.getChannelName(), amount.toPlainString(),
                        best.getAmountReceived().toPlainString(),
                        savings.toPlainString(), worst.getChannelName());
            }
            case FASTEST -> String.format(
                    "Based on your priority for speed, %s is your best option — it's %s and you'll still receive %s PKR.",
                    best.getChannelName(), best.getEstimatedSpeed().toLowerCase(),
                    best.getAmountReceived().toPlainString());
            case BALANCED -> {
                BigDecimal savings = computeSavings(best, worst);
                yield String.format(
                        "%s offers the best balance of speed and value for $%s — delivering %s PKR in %s, saving you %s PKR over %s.",
                        best.getChannelName(), amount.toPlainString(),
                        best.getAmountReceived().toPlainString(),
                        best.getEstimatedSpeed().toLowerCase(),
                        savings.toPlainString(), worst.getChannelName());
            }
        };
    }

    private BigDecimal computeSavings(RemittanceResult best, RemittanceResult worst) {
        return best.getAmountReceived().subtract(worst.getAmountReceived());
    }
}
