package com.remittance.optimizer.service;

import com.remittance.optimizer.dto.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ComparisonService {

    // Fixed mock market rate: 1 USD = 278 PKR (to be replaced with live FX API later)
    private static final BigDecimal MARKET_RATE = new BigDecimal("278.00");

    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);
    private static final int SCALE = 2;

    private static final BigDecimal WEIGHT_AMOUNT = new BigDecimal("0.5");
    private static final BigDecimal WEIGHT_SPEED = new BigDecimal("0.5");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000"); // 1 billion USD

    // Speed ranks: 1 = fastest, higher = slower
    private static final Map<String, Integer> SPEED_RANKS = Map.of(
            "JazzCash/Easypaisa", 1,
            "Western Union", 2,
            "Remitly", 2,
            "Wise", 3,
            "Bank Wire", 4
    );

    private record ChannelData(String name, BigDecimal rateDiscount, BigDecimal feeValue,
                               FeeType feeType, String speed) {}

    private static final List<ChannelData> CHANNELS = List.of(
            new ChannelData("Bank Wire",           new BigDecimal("0.015"), new BigDecimal("30.00"), FeeType.FLAT,       "2-5 days"),
            new ChannelData("Western Union",       new BigDecimal("0.010"), new BigDecimal("8.00"),  FeeType.FLAT,       "Minutes to 1 day"),
            new ChannelData("Wise",                new BigDecimal("0.003"), new BigDecimal("0.006"), FeeType.PERCENTAGE, "1-2 days"),
            new ChannelData("JazzCash/Easypaisa",  new BigDecimal("0.005"), new BigDecimal("2.00"),  FeeType.FLAT,       "Instant"),
            new ChannelData("Remitly",             new BigDecimal("0.008"), new BigDecimal("5.00"),  FeeType.FLAT,       "Minutes to 1 day")
    );

    public RemittanceResponse compare(RemittanceRequest request) {
        validateRequest(request);

        BigDecimal amount = request.getAmount();
        Priority priority = request.getPriority() != null ? request.getPriority() : Priority.CHEAPEST;

        List<RemittanceResult> results = CHANNELS.stream()
                .map(ch -> buildChannelResult(ch, amount))
                .collect(Collectors.toCollection(ArrayList::new));

        if (results.isEmpty()) {
            throw new IllegalStateException("No remittance channels are currently configured");
        }

        sortByPriority(results, priority);

        RemittanceResult bestChannel = results.get(0);
        RemittanceResult worstChannel = results.get(results.size() - 1);

        String recommendation = buildRecommendation(bestChannel, worstChannel, amount, priority);

        return RemittanceResponse.builder()
                .originalRequest(request)
                .results(results)
                .bestChannel(bestChannel)
                .recommendation(recommendation)
                .build();
    }

    private void validateRequest(RemittanceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (request.getAmount() == null) {
            throw new IllegalArgumentException("Amount is required");
        }
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        if (request.getAmount().compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("Amount exceeds the maximum allowed value of " + MAX_AMOUNT);
        }
        if (request.getSourceCurrency() == null || request.getSourceCurrency().isBlank()) {
            throw new IllegalArgumentException("Source currency is required");
        }
        if (!"USD".equalsIgnoreCase(request.getSourceCurrency())) {
            throw new IllegalArgumentException("Only USD is supported as the source currency in this demo");
        }
        if (request.getDestinationCurrency() == null || request.getDestinationCurrency().isBlank()) {
            throw new IllegalArgumentException("Destination currency is required");
        }
        if (!"PKR".equalsIgnoreCase(request.getDestinationCurrency())) {
            throw new IllegalArgumentException("Only PKR is supported as the destination currency in this demo");
        }
    }

    // ---- Sorting ----

    private void sortByPriority(List<RemittanceResult> results, Priority priority) {
        switch (priority) {
            case CHEAPEST ->
                    results.sort(Comparator
                            .comparing(RemittanceResult::getAmountReceived).reversed()
                            .thenComparing(RemittanceResult::getChannelName));

            case FASTEST ->
                    results.sort(Comparator
                            .comparingInt((RemittanceResult r) -> getSpeedRank(r.getChannelName()))
                            .thenComparing(Comparator.comparing(RemittanceResult::getAmountReceived).reversed())
                            .thenComparing(RemittanceResult::getChannelName));

            case BALANCED -> {
                BigDecimal maxAmount = results.stream()
                        .map(RemittanceResult::getAmountReceived)
                        .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                BigDecimal minAmount = results.stream()
                        .map(RemittanceResult::getAmountReceived)
                        .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                BigDecimal amountRange = maxAmount.subtract(minAmount);

                int maxSpeed = results.stream().mapToInt(r -> getSpeedRank(r.getChannelName())).max().orElse(1);
                int minSpeed = results.stream().mapToInt(r -> getSpeedRank(r.getChannelName())).min().orElse(1);
                int speedRange = maxSpeed - minSpeed;

                results.sort((a, b) -> {
                    BigDecimal scoreA = computeBalancedScore(a, amountRange, minAmount, maxSpeed, minSpeed, speedRange);
                    BigDecimal scoreB = computeBalancedScore(b, amountRange, minAmount, maxSpeed, minSpeed, speedRange);
                    int comparison = scoreB.compareTo(scoreA);
                    return comparison != 0 ? comparison : a.getChannelName().compareTo(b.getChannelName());
                });
            }
        }
    }

    private int getSpeedRank(String channelName) {
        Integer rank = SPEED_RANKS.get(channelName);
        if (rank == null) {
            throw new IllegalStateException("No speed rank configured for channel: " + channelName);
        }
        return rank;
    }

    /**
     * Normalized combined score: 50% weight on amount received, 50% on speed.
     * Both dimensions are normalized to [0, 1] before combining.
     * The entire calculation stays in BigDecimal to avoid floating-point drift.
     */
    private BigDecimal computeBalancedScore(RemittanceResult result,
                                            BigDecimal amountRange, BigDecimal minAmount,
                                            int maxSpeed, int minSpeed, int speedRange) {
        BigDecimal amountScore;
        if (amountRange.compareTo(BigDecimal.ZERO) == 0) {
            amountScore = BigDecimal.ONE;
        } else {
            amountScore = result.getAmountReceived().subtract(minAmount)
                    .divide(amountRange, MC);
        }

        BigDecimal speedScore;
        if (speedRange == 0) {
            speedScore = BigDecimal.ONE;
        } else {
            // Lower speedRank = faster = better, so invert
            BigDecimal speedOffset = BigDecimal.valueOf(maxSpeed - getSpeedRank(result.getChannelName()));
            speedScore = speedOffset.divide(BigDecimal.valueOf(speedRange), MC);
        }

        return amountScore.multiply(WEIGHT_AMOUNT, MC)
                .add(speedScore.multiply(WEIGHT_SPEED, MC), MC);
    }

    // ---- Channel calculation ----

    private RemittanceResult buildChannelResult(ChannelData ch, BigDecimal amount) {
        // Channel exchange rate = market rate * (1 - discount)
        BigDecimal channelRate = MARKET_RATE.multiply(BigDecimal.ONE.subtract(ch.rateDiscount()), MC)
                .setScale(4, RoundingMode.HALF_UP);

        // Calculate fee in source currency
        BigDecimal feeAmount;
        if (ch.feeType() == FeeType.PERCENTAGE) {
            feeAmount = amount.multiply(ch.feeValue(), MC).setScale(SCALE, RoundingMode.HALF_UP);
        } else {
            feeAmount = ch.feeValue().setScale(SCALE, RoundingMode.HALF_UP);
        }

        // Amount after deducting fee, then converted at channel rate
        BigDecimal amountAfterFee = amount.subtract(feeAmount);
        BigDecimal amountReceived = amountAfterFee.multiply(channelRate, MC)
                .setScale(SCALE, RoundingMode.HALF_UP);

        return RemittanceResult.builder()
                .channelName(ch.name())
                .exchangeRate(channelRate)
                .feeAmount(feeAmount)
                .feeType(ch.feeType())
                .amountReceived(amountReceived)
                .estimatedSpeed(ch.speed())
                .build();
    }

    // ---- Recommendation text ----

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
