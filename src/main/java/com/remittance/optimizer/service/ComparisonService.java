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
            new ChannelData("Wise",                new BigDecimal("0.003"), new BigDecimal("0.006"), FeeType.PERCENTAGE,  "1-2 days"),
            new ChannelData("JazzCash/Easypaisa",  new BigDecimal("0.005"), new BigDecimal("2.00"),  FeeType.FLAT,       "Instant"),
            new ChannelData("Remitly",             new BigDecimal("0.008"), new BigDecimal("5.00"),  FeeType.FLAT,       "Minutes to 1 day")
    );

    public RemittanceResponse compare(RemittanceRequest request) {
        BigDecimal amount = request.getAmount();
        Priority priority = request.getPriority() != null ? request.getPriority() : Priority.CHEAPEST;

        List<RemittanceResult> results = CHANNELS.stream()
                .map(ch -> buildChannelResult(ch, amount))
                .collect(Collectors.toCollection(ArrayList::new));

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

    // ---- Sorting ----

    private void sortByPriority(List<RemittanceResult> results, Priority priority) {
        switch (priority) {
            case CHEAPEST ->
                    results.sort(Comparator.comparing(RemittanceResult::getAmountReceived).reversed());

            case FASTEST ->
                    results.sort(Comparator
                            .comparingInt((RemittanceResult r) -> getSpeedRank(r.getChannelName()))
                            .thenComparing(Comparator.comparing(RemittanceResult::getAmountReceived).reversed()));

            case BALANCED -> {
                BigDecimal maxAmount = results.stream()
                        .map(RemittanceResult::getAmountReceived)
                        .max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
                BigDecimal minAmount = results.stream()
                        .map(RemittanceResult::getAmountReceived)
                        .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                BigDecimal amountRange = maxAmount.subtract(minAmount);

                int maxSpeed = results.stream().mapToInt(r -> getSpeedRank(r.getChannelName())).max().orElse(1);
                int minSpeed = results.stream().mapToInt(r -> getSpeedRank(r.getChannelName())).min().orElse(1);
                double speedRange = maxSpeed - minSpeed;

                results.sort((a, b) -> {
                    double scoreA = computeBalancedScore(a, amountRange, minAmount, speedRange, minSpeed);
                    double scoreB = computeBalancedScore(b, amountRange, minAmount, speedRange, minSpeed);
                    return Double.compare(scoreB, scoreA);
                });
            }
        }
    }

    private int getSpeedRank(String channelName) {
        return SPEED_RANKS.getOrDefault(channelName, 5);
    }

    /**
     * Normalized combined score: 50% weight on amount received, 50% on speed.
     * Both dimensions are normalized to [0, 1] before combining.
     */
    private double computeBalancedScore(RemittanceResult result,
                                        BigDecimal amountRange, BigDecimal minAmount,
                                        double speedRange, int minSpeed) {
        double amountScore;
        if (amountRange.compareTo(BigDecimal.ZERO) == 0) {
            amountScore = 1.0;
        } else {
            amountScore = result.getAmountReceived().subtract(minAmount)
                    .divide(amountRange, MC).doubleValue();
        }

        double speedScore;
        if (speedRange == 0) {
            speedScore = 1.0;
        } else {
            // Lower speedRank = faster = better, so invert
            speedScore = 1.0 - ((getSpeedRank(result.getChannelName()) - minSpeed) / speedRange);
        }

        return (0.5 * amountScore) + (0.5 * speedScore);
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
            feeAmount = ch.feeValue();
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
                BigDecimal savings = best.getAmountReceived().subtract(worst.getAmountReceived());
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
                BigDecimal savings = best.getAmountReceived().subtract(worst.getAmountReceived());
                yield String.format(
                        "%s offers the best balance of speed and value for $%s — delivering %s PKR in %s, saving you %s PKR over %s.",
                        best.getChannelName(), amount.toPlainString(),
                        best.getAmountReceived().toPlainString(),
                        best.getEstimatedSpeed().toLowerCase(),
                        savings.toPlainString(), worst.getChannelName());
            }
        };
    }
}
