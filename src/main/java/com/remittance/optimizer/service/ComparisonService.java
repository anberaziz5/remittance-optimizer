package com.remittance.optimizer.service;

import com.remittance.optimizer.dto.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ComparisonService {

    // Fixed mock market rate: 1 USD = 278 PKR (to be replaced with live FX API later)
    private static final BigDecimal MARKET_RATE = new BigDecimal("278.00");

    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);
    private static final int SCALE = 2;

    public RemittanceResponse compare(RemittanceRequest request) {
        BigDecimal amount = request.getAmount();
        List<RemittanceResult> results = new ArrayList<>();

        results.add(buildChannelResult("Bank Wire", amount, new BigDecimal("0.015"),
                new BigDecimal("30.00"), FeeType.FLAT, "2-5 days"));

        results.add(buildChannelResult("Western Union", amount, new BigDecimal("0.010"),
                new BigDecimal("8.00"), FeeType.FLAT, "Minutes to 1 day"));

        results.add(buildChannelResult("Wise", amount, new BigDecimal("0.003"),
                new BigDecimal("0.006"), FeeType.PERCENTAGE, "1-2 days"));

        results.add(buildChannelResult("JazzCash/Easypaisa", amount, new BigDecimal("0.005"),
                new BigDecimal("2.00"), FeeType.FLAT, "Instant"));

        results.add(buildChannelResult("Remitly", amount, new BigDecimal("0.008"),
                new BigDecimal("5.00"), FeeType.FLAT, "Minutes to 1 day"));

        // Sort by amountReceived descending
        results.sort(Comparator.comparing(RemittanceResult::getAmountReceived).reversed());

        RemittanceResult bestChannel = results.get(0);
        RemittanceResult worstChannel = results.get(results.size() - 1);

        String recommendation = buildRecommendation(bestChannel, worstChannel, amount);

        return RemittanceResponse.builder()
                .originalRequest(request)
                .results(results)
                .bestChannel(bestChannel)
                .recommendation(recommendation)
                .build();
    }

    private RemittanceResult buildChannelResult(String channelName, BigDecimal amount,
                                                  BigDecimal rateDiscount, BigDecimal feeValue,
                                                  FeeType feeType, String speed) {
        // Channel exchange rate = market rate * (1 - discount)
        BigDecimal channelRate = MARKET_RATE.multiply(BigDecimal.ONE.subtract(rateDiscount), MC)
                .setScale(4, RoundingMode.HALF_UP);

        // Calculate fee in source currency
        BigDecimal feeAmount;
        if (feeType == FeeType.PERCENTAGE) {
            feeAmount = amount.multiply(feeValue, MC).setScale(SCALE, RoundingMode.HALF_UP);
        } else {
            feeAmount = feeValue;
        }

        // Amount after deducting fee, then converted at channel rate
        BigDecimal amountAfterFee = amount.subtract(feeAmount);
        BigDecimal amountReceived = amountAfterFee.multiply(channelRate, MC)
                .setScale(SCALE, RoundingMode.HALF_UP);

        return RemittanceResult.builder()
                .channelName(channelName)
                .exchangeRate(channelRate)
                .feeAmount(feeAmount)
                .feeType(feeType)
                .amountReceived(amountReceived)
                .estimatedSpeed(speed)
                .build();
    }

    private String buildRecommendation(RemittanceResult best, RemittanceResult worst, BigDecimal amount) {
        BigDecimal savings = worst.getAmountReceived().subtract(best.getAmountReceived()).negate();
        return String.format(
                "%s is the best option for sending $%s — you'll receive %s PKR, which is %s PKR more than %s (the most expensive option).",
                best.getChannelName(),
                amount.toPlainString(),
                best.getAmountReceived().toPlainString(),
                savings.toPlainString(),
                worst.getChannelName()
        );
    }
}
