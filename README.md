# Remittance Optimizer

An AI-powered app comparing exchange rates and transfer fees to help overseas Pakistanis save money on remittances.

## Architecture

The project is split into two Spring Boot microservices:

- **rate-service** (port `8081`) — owns exchange rates, fees, and ranking logic for all five remittance channels.
- **remittance-api** (port `8080`) — public-facing service that serves the frontend and delegates rate comparison to `rate-service` via REST.

## Running the Demo

Make sure Java 17 is installed and `JAVA_HOME` is set, then open **two separate terminals** in the project root.

### 1. Build everything once

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
.\mvnw.cmd clean install -DskipTests
```

### 2. Start rate-service first

`rate-service` must be running before `remittance-api` can call it.

```powershell
.\mvnw.cmd spring-boot:run -pl rate-service
```

Wait for `Tomcat started on port 8081` in the logs.

### 3. Start remittance-api second

In another terminal:

```powershell
.\mvnw.cmd spring-boot:run -pl remittance-api
```

Wait for `Tomcat started on port 8080` in the logs.

## Verifying Both Services

| Check | URL / Command | Expected Result |
|-------|---------------|-----------------|
| Frontend | `http://localhost:8080` | Remittance Optimizer form loads |
| Compare API | `POST http://localhost:8080/api/remittance/compare` | Ranked results + recommendation |
| Rate service direct | `POST http://localhost:8081/api/rates/compare` | Ranked `List<RemittanceResult>` |

Example request body for either compare endpoint:

```json
{
  "amount": 500,
  "sourceCountry": "USA",
  "sourceCurrency": "USD",
  "destinationCountry": "Pakistan",
  "destinationCurrency": "PKR",
  "priority": "CHEAPEST"
}
```

Supported priorities: `CHEAPEST`, `FASTEST`, `BALANCED`.

If `rate-service` is unavailable, `remittance-api` returns:

```json
{ "error": "Rate service is currently unavailable. Please try again later." }
```
