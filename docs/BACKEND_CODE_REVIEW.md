# Backend Code Review: Remittance Optimizer

## Summary of Files Changed

| File | Change |
|------|--------|
| `dto/RemittanceRequest.java` | Added Jakarta Bean Validation annotations |
| `controller/RemittanceController.java` | Added `@Valid` to enforce validation |
| `service/ComparisonService.java` | Added validation, BigDecimal-only scoring, deterministic sorting, guards |
| `exception/GlobalExceptionHandler.java` | **NEW** — structured error responses with proper HTTP codes |

---

## 1. Input Validation

### Issue 1.1: No validation annotations on `RemittanceRequest`

**Problem:** `amount` could be null, negative, zero, or enormous; strings could be empty; and the controller had no `@Valid`, so even added annotations wouldn't fire. In a financial app this is dangerous — a null amount would cause an NPE, and a negative amount would produce nonsensical results.

**Fix:** Added `@NotNull`, `@Positive`, `@Digits`, and `@NotBlank` to the DTO, and `@Valid` on the controller parameter.

**Before:**
```java
public class RemittanceRequest {
    private BigDecimal amount;
    private String sourceCountry;
    private String sourceCurrency;
    private Priority priority = Priority.CHEAPEST;
}
```

**After:**
```java
public class RemittanceRequest {
    @NotNull @Positive @Digits(integer = 12, fraction = 2)
    private BigDecimal amount;

    @NotNull @NotBlank
    private String sourceCountry;

    @NotNull @NotBlank
    private String sourceCurrency;

    @NotNull
    private Priority priority = Priority.CHEAPEST;
}
```

```java
@PostMapping("/compare")
public ResponseEntity<RemittanceResponse> compare(@Valid @RequestBody RemittanceRequest request) {
    ...
}
```

### Issue 1.2: Unsupported currency pairs were silently accepted

**Problem:** The mock rate is fixed at `1 USD = 278 PKR`. If a caller sent `sourceCurrency: "GBP"`, the service still multiplied by 278, silently producing wrong results.

**Fix:** Added service-level validation to reject anything other than USD→PKR for this demo.

```java
if (!"USD".equalsIgnoreCase(request.getSourceCurrency())) {
    throw new IllegalArgumentException("Only USD is supported as the source currency in this demo");
}
if (!"PKR".equalsIgnoreCase(request.getDestinationCurrency())) {
    throw new IllegalArgumentException("Only PKR is supported as the destination currency in this demo");
}
```

### Issue 1.3: Invalid priority enum values caused Spring's generic JSON error

**Problem:** Sending `"CHEAPESTO"` threw a low-level `HttpMessageNotReadableException` with a stack trace.

**Fix:** Added `GlobalExceptionHandler` that detects invalid enum values and returns a clean 400:

```json
{"error": "Invalid priority value. Allowed values: CHEAPEST, FASTEST, BALANCED"}
```

---

## 2. Financial Precision

### Issue 2.1: Balanced scoring converted `BigDecimal` to `double`

**Problem:** The normalized amount score used `.doubleValue()`, introducing floating-point imprecision into ranking logic.

**Before:**
```java
amountScore = result.getAmountReceived().subtract(minAmount)
        .divide(amountRange, MC).doubleValue();
```

**After:** The entire balanced score stays in `BigDecimal`.

```java
private BigDecimal computeBalancedScore(...) {
    BigDecimal amountScore = amountRange.compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ONE
            : result.getAmountReceived().subtract(minAmount).divide(amountRange, MC);

    BigDecimal speedScore = speedRange == 0
            ? BigDecimal.ONE
            : BigDecimal.valueOf(maxSpeed - getSpeedRank(...))
                    .divide(BigDecimal.valueOf(speedRange), MC);

    return amountScore.multiply(WEIGHT_AMOUNT, MC)
            .add(speedScore.multiply(WEIGHT_SPEED, MC), MC);
}
```

### Issue 2.2: FLAT fee amounts didn't explicitly set scale

**Problem:** `feeAmount = ch.feeValue()` relied on the literal already having 2 decimal places.

**Fix:** Explicit scale for all fees:

```java
feeAmount = ch.feeValue().setScale(SCALE, RoundingMode.HALF_UP);
```

### Issue 2.3: Division by zero safety

**Problem:** If all channels ever produced the same `amountReceived`, `amountRange` would be zero. This was already guarded, but the guard now lives inside pure `BigDecimal` logic.

---

## 3. Error Handling

### Issue 3.1: No global exception handler

**Problem:** Any unhandled exception returned Spring's Whitelabel page or a raw stack trace.

**Fix:** Added `GlobalExceptionHandler` with handlers for:

- `MethodArgumentNotValidException` → **400** with field-level details
- `HttpMessageNotReadableException` → **400** with friendly enum-error message
- `IllegalArgumentException` → **400**
- `IllegalStateException` → **500**
- Generic `Exception` → **500** with a safe, non-leaky message

### Verified behavior:

| Test | Status | Body |
|------|--------|------|
| Negative amount | 400 | `{"error":"Validation failed","details":{"amount":"..."}}` |
| Zero amount | 400 | same |
| Missing amount | 400 | same |
| GBP currency | 400 | `{"error":"Only USD is supported..."}` |
| Invalid priority | 400 | `{"error":"Invalid priority value..."}` |

---

## 4. Edge Cases

### Issue 4.1: Non-deterministic tie-breaking

**Problem:** If two channels tied on `amountReceived` or `speedRank`, Java's sort is stable but the ordering wasn't contractually guaranteed across JVMs or refactors.

**Fix:** Added channel name as the final tie-breaker in every sort branch:

```java
.thenComparing(RemittanceResult::getChannelName)
```

### Issue 4.2: Empty channel list would crash

**Problem:** `results.get(0)` would throw `IndexOutOfBoundsException` if `CHANNELS` were empty.

**Fix:** Guard clause:

```java
if (results.isEmpty()) {
    throw new IllegalStateException("No remittance channels are currently configured");
}
```

### Issue 4.3: Silent fallback speed rank

**Problem:** `SPEED_RANKS.getOrDefault(channelName, 5)` could hide a typo in a channel name.

**Fix:** Fail fast:

```java
Integer rank = SPEED_RANKS.get(channelName);
if (rank == null) {
    throw new IllegalStateException("No speed rank configured for channel: " + channelName);
}
```

---

## 5. Code Quality

### Issue 5.1: Duplicated savings calculation

**Problem:** `CHEAPEST` and `BALANCED` both computed savings inline.

**Fix:** Extracted helper:

```java
private BigDecimal computeSavings(RemittanceResult best, RemittanceResult worst) {
    return best.getAmountReceived().subtract(worst.getAmountReceived());
}
```

---

## Verification

After restarting, all three priority modes still produce the expected rankings for `$1000`:

| Priority | 1st | 2nd | 3rd | 4th | 5th |
|----------|-----|-----|-----|-----|-----|
| CHEAPEST | JazzCash/Easypaisa | Wise | Remitly | Western Union | Bank Wire |
| FASTEST | JazzCash/Easypaisa | Remitly | Western Union | Wise | Bank Wire |
| BALANCED | JazzCash/Easypaisa | Remitly | Western Union | Wise | Bank Wire |

JazzCash/Easypaisa dominates all three modes at this amount because it is both cheapest and fastest. The key difference is in the tie-breaking and ordering of the remaining channels, which now behaves deterministically.
