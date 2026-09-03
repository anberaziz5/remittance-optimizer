# Remittance Optimizer — Architecture & Onboarding Guide

Welcome to the Remittance Optimizer. This guide explains the system's architecture, request flow, data model, business logic, and frontend behavior so you can get oriented quickly.

---

## 1. High-Level Overview

The Remittance Optimizer is a small Spring Boot system split into two microservices. A user enters how much money they want to send, where they're sending it from, and what matters most to them (cheapest, fastest, or balanced). The system then ranks available remittance channels and recommends the best one.

### Main Components

```
┌─────────────────────────────────────────────────────────────┐
│  Frontend: remittance-api/src/main/resources/static/index.html │  ← Single-page form
└─────────────────────────┬───────────────────────────────────┘
                          │ POST /api/remittance/compare
                          ▼
┌─────────────────────────────────────────────────────────────┐
│  remittance-api (port 8080)                                  │
│  ├─ RemittanceController — public REST entry point           │
│  ├─ RemittanceService — calls rate-service, builds recommendation │
│  └─ RestTemplateConfig — service-to-service HTTP client      │
└─────────────────────────┬───────────────────────────────────┘
                          │ POST /api/rates/compare
                          ▼
┌─────────────────────────────────────────────────────────────┐
│  rate-service (port 8081)                                    │
│  ├─ RateController — internal REST entry point               │
│  ├─ ComparisonService — channel data, financial math, ranking │
│  └─ CorsConfig — allows cross-origin calls from localhost:8080 │
└─────────────────────────────────────────────────────────────┘
```

| Layer | Responsibility |
|-------|----------------|
| **remittance-api Controller** | Receives public HTTP requests, validates input, delegates to RemittanceService |
| **remittance-api Service** | Calls `rate-service`, takes ranked results, and builds the recommendation text |
| **rate-service Controller** | Receives internal HTTP requests from `remittance-api` |
| **rate-service Service** | Holds channel data, performs financial math, ranks results |
| **DTOs** | Plain data objects that define the API contract between frontend, remittance-api, and rate-service |
| **Frontend** | Static HTML/CSS/JS page served from `remittance-api/src/main/resources/static` |

---

## 2. Request Flow: From Form to Results

When a user clicks **Compare Rates**, here's what happens:

### Step 1 — Frontend builds the request
The browser reads the form fields:
- Amount to send
- Source country and currency
- Destination country/currency (currently hardcoded to Pakistan/PKR)
- Selected priority: `CHEAPEST`, `FASTEST`, or `BALANCED`

It assembles a JSON body and sends it via `POST` to `/api/remittance/compare`.

### Step 2 — Controller receives the request
`RemittanceController.compare(...)` accepts a `RemittanceRequest` object. Spring Boot automatically deserializes the incoming JSON into this DTO. The controller then calls `remittanceService.compare(request)`.

### Step 3 — remittance-api delegates to rate-service
`RemittanceService.compare(...)` forwards the request to `rate-service` at `POST /api/rates/compare` using `RestTemplate`. It validates the response and converts the returned `RemittanceResult[]` into a `List<RemittanceResult>`.

### Step 4 — rate-service calculates and ranks channel results
Inside `rate-service`, `ComparisonService.compare(...)` loops through the five supported channels:

- Bank Wire
- Western Union
- Wise
- JazzCash / Easypaisa
- Remitly

For each channel, it:
1. Applies that channel's exchange-rate markup to the mock market rate
2. Calculates the fee (flat fee or percentage of amount)
3. Computes `amountReceived` = `(amount − fee) × channelRate`
4. Stores the result in a `RemittanceResult` object

### Step 4 — Service ranks results by priority
The service sorts the list of results based on the user's selected priority:

- **CHEAPEST** → highest `amountReceived` first
- **FASTEST** → lowest `speedRank` first, ties broken by `amountReceived`
- **BALANCED** → combined score of normalized amount and speed

### Step 5 — Recommendation text is generated
Back in `remittance-api`, `RemittanceService.buildRecommendation(...)` builds a plain-language sentence explaining why the top result is best, phrased according to the selected priority.

### Step 6 — Response returns to frontend
A `RemittanceResponse` is returned containing:
- The original request
- The ranked list of results
- The best channel
- The recommendation text

### Step 7 — Frontend renders
JavaScript receives the JSON, clears any previous results, and dynamically builds a card for each channel. The first card is highlighted with a "Best Value" badge.

---

## 3. Data Model

### `RemittanceRequest`
Represents what the user is asking for.

| Field | Type | Description |
|-------|------|-------------|
| `amount` | `BigDecimal` | How much money to send |
| `sourceCountry` | `String` | Country sending from (e.g., USA, UK) |
| `sourceCurrency` | `String` | Currency code (e.g., USD, GBP) |
| `destinationCountry` | `String` | Defaults to `"Pakistan"` |
| `destinationCurrency` | `String` | Defaults to `"PKR"` |
| `priority` | `Priority` | Defaults to `CHEAPEST` |

The defaults ensure older requests (or requests that omit the field) still work exactly as before.

### `RemittanceResult`
Represents one row in the comparison table.

| Field | Type | Description |
|-------|------|-------------|
| `channelName` | `String` | Human-readable provider name |
| `exchangeRate` | `BigDecimal` | Rate offered by this channel |
| `feeAmount` | `BigDecimal` | Fee in source currency |
| `feeType` | `FeeType` | `FLAT` or `PERCENTAGE` |
| `amountReceived` | `BigDecimal` | Final amount the recipient gets |
| `estimatedSpeed` | `String` | Human-readable delivery time |

### `RemittanceResponse`
The final payload sent back to the frontend.

| Field | Type | Description |
|-------|------|-------------|
| `originalRequest` | `RemittanceRequest` | Echoes what the user asked for |
| `results` | `List<RemittanceResult>` | Ranked list of channels |
| `bestChannel` | `RemittanceResult` | Top result from the ranked list |
| `recommendation` | `String` | Plain-language explanation |

### Enums

- **`FeeType`** — `FLAT` for fixed fees, `PERCENTAGE` for fees that scale with amount
- **`Priority`** — `CHEAPEST`, `FASTEST`, `BALANCED`

---

## 4. Business Logic

### Channel Configuration

The service keeps a static list of channels. Each channel defines:

- A percentage discount off the mock market rate (e.g., Wise uses market rate − 0.3%)
- A fee structure (flat or percentage)
- An estimated speed string
- A `speedRank` used for priority sorting

Current speed ranks (1 = fastest):

| Channel | Speed Rank | Speed |
|---------|-----------|-------|
| JazzCash / Easypaisa | 1 | Instant |
| Western Union | 2 | Minutes to 1 day |
| Remitly | 2 | Minutes to 1 day |
| Wise | 3 | 1–2 days |
| Bank Wire | 4 | 2–5 days |

### Priority-Based Sorting

#### CHEAPEST
```java
results.sort(Comparator.comparing(RemittanceResult::getAmountReceived).reversed());
```
Simple and intuitive: whoever delivers the most PKR wins.

#### FASTEST
```java
results.sort(Comparator
    .comparingInt((RemittanceResult r) -> getSpeedRank(r.getChannelName()))
    .thenComparing(Comparator.comparing(RemittanceResult::getAmountReceived).reversed()));
```
Sorts by speed first. When two channels are equally fast, the one that delivers more money comes first.

#### BALANCED
This mode tries to find the channel that gives a good trade-off between cost and speed.

1. Normalize `amountReceived` to a 0–1 scale across all channels
2. Normalize `speedRank` to a 0–1 scale (inverted so faster = higher score)
3. Combine both with 50% weight each
4. Sort by combined score descending

If a channel is both the cheapest and the fastest, it will dominate the balanced score as well. Otherwise, balanced mode may favor a slightly more expensive but much faster channel over the absolute cheapest.

### Why BigDecimal?

All monetary and rate calculations use `java.math.BigDecimal`, never `double` or `float`. This is important because:

- Floating-point types like `double` cannot represent decimal fractions exactly (e.g., `0.1 + 0.2 != 0.3`)
- In financial systems, tiny rounding errors compound and can cause real money discrepancies
- `BigDecimal` lets us control precision and rounding mode explicitly

For example, channel rates are computed with `MathContext(10, HALF_UP)` and final amounts are rounded to 2 decimal places, matching real-world currency precision.

---

## 5. Frontend

The frontend is a single static file: `src/main/resources/static/index.html`.

### Communication
It uses the browser's `fetch()` API to call the backend:

```javascript
fetch('/api/remittance/compare', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount, sourceCountry, sourceCurrency, priority, ... })
})
```

Because the file is in `static/`, Spring Boot automatically serves it at the root path (`http://localhost:8080/`).

### Rendering
After receiving the response:

1. The recommendation banner is populated
2. The results title is shown
3. A card is created for each result in the returned order
4. The first card gets the `best` CSS class, adding a green border and "Best Value" badge

No external CSS or JS libraries are used — everything is custom-built for a lightweight, fintech-style look.

---

## 6. Future Growth: Natural Service Boundaries

If this application grows into a larger production system, these seams would be natural places to split it:

### FX Rate Service
Currently the market rate is hardcoded (`1 USD = 278 PKR`). A dedicated service could fetch live exchange rates from providers like XE, OpenExchangeRates, or central banks and cache them.

### Channel Provider Service
Each remittance provider (Wise, Remitly, Western Union, etc.) likely exposes its own API for real-time rates and fees. A provider service could own the integration with each one, abstracting away their individual quirks.

### Comparison Engine Service
This is essentially the current `ComparisonService` grown into its own service. It would receive normalized rate/fee data from the FX and provider services and run the ranking/scoring logic.

### User / Preferences Service
As users return, you might want to save favorite corridors, default countries, or preferred priorities.

### Frontend / Static Hosting
The single HTML page could become a React/Vue/Angular app or a mobile app, hosted separately and communicating with the backend over the same REST API.

---

## Quick Start

To run locally you need **two terminals** because there are now two services.

### 1. Build everything

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
.\mvnw.cmd clean install -DskipTests
```

### 2. Start rate-service first

`rate-service` must be up before `remittance-api` can call it.

```powershell
.\mvnw.cmd spring-boot:run -pl rate-service
```

Wait for `Tomcat started on port 8081`.

### 3. Start remittance-api second

In a second terminal:

```powershell
.\mvnw.cmd spring-boot:run -pl remittance-api
```

Wait for `Tomcat started on port 8080`.

### 4. Open the app

Browse to `http://localhost:8080`.

You can also hit the public API directly:

```powershell
$body = @{ amount=500; sourceCountry="USA"; sourceCurrency="USD"; destinationCountry="Pakistan"; destinationCurrency="PKR"; priority="CHEAPEST" } | ConvertTo-Json -Compress
Invoke-WebRequest -Uri http://localhost:8080/api/remittance/compare -Method POST -ContentType 'application/json' -Body $body -UseBasicParsing
```

Or query `rate-service` directly on port `8081`:

```powershell
Invoke-WebRequest -Uri http://localhost:8081/api/rates/compare -Method POST -ContentType 'application/json' -Body $body -UseBasicParsing
```

---

That's the whole system. Channel data and ranking rules live in `rate-service`, while presentation and recommendation text live in `remittance-api`. This split keeps core rate logic isolated and makes it easy to add new channels, swap in a live FX provider, or change the frontend independently.
