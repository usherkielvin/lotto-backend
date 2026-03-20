# Lotto Backend API

A clean Spring Boot backend for the Lotto Simulator platform.

This service powers authentication, game catalog, bet placement, bet settlement, wallet funding, profile analytics, and admin-managed official results.

## Overview

The backend is designed for a demo-ready lottery simulation workflow:

- User login, registration, and demo session support
- Multi-game lotto catalog delivery
- Bet placement and settlement lifecycle
- Draw result generation with deterministic fallback logic
- Wallet operations (deposit, withdraw) and funding history
- Player profile stats and lucky-number insights
- Admin tools for official result management

## Stack

- Java 21
- Spring Boot 3.4.1
- Spring Web
- Spring Data JPA
- MySQL
- Spring Security (BCrypt password hashing)
- Maven Wrapper

## Architecture

Layered structure:

- Controllers: HTTP API routing
- Services: business rules and settlement logic
- Repositories: JPA persistence access
- Entities: domain models and schema mapping

Core packages:

- src/main/java/com/lotto/controller
- src/main/java/com/lotto/service
- src/main/java/com/lotto/repository
- src/main/java/com/lotto/entity
- src/main/java/com/lotto/config

## Domain Models

Primary entities:

- User
- Balance
- Bet
- LottoGame
- OfficialResult
- FundingTransaction

Seed and schema initialization are defined in src/main/resources/schema.sql.

## API Design

Base URL:

- http://localhost:8099/api

Authentication approach:

- Login and register endpoints return session payload with userId.
- Protected business endpoints use X-User-Id request header.
- Admin endpoints additionally enforce role=admin server-side checks.

### Route Summary

Auth:

- POST /api/auth/login
- POST /api/auth/register
- POST /api/auth/demo
- GET /api/auth/hash

Games:

- GET /api/games

Bets and Wallet:

- POST /api/bets
- GET /api/bets
- GET /api/bets/history
- GET /api/bets/balance
- POST /api/bets/balance
- GET /api/bets/funding

Profile:

- GET /api/profile

Admin:

- GET /api/admin/results
- POST /api/admin/results
- DELETE /api/admin/results/{id}

## Business Rules

- Draw settlement targets 9:00 PM draw cutoff logic.
- Pending bets are settled when fetched after draw time.
- Official results are read from admin-managed records when present.
- If no official result exists, deterministic seeded fallback numbers are generated.
- Funding adjustments enforce a minimum amount of 50.

## Local Setup

### Prerequisites

- Java 21
- MySQL 8+
- Git

### 1) Create database

Create the database used by application.properties:

- lottodb

### 2) Configure datasource

Edit src/main/resources/application.properties with your local MySQL credentials.

Default app port:

- 8099

### 3) Run the API

Windows PowerShell:

```powershell
.\mvnw spring-boot:run
```

macOS/Linux:

```bash
./mvnw spring-boot:run
```

### 4) Run tests

Windows PowerShell:

```powershell
.\mvnw test
```

macOS/Linux:

```bash
./mvnw test
```

## Example Requests

Register:

```bash
curl -X POST http://localhost:8099/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"player1","password":"secret123","displayName":"Player One"}'
```

Login:

```bash
curl -X POST http://localhost:8099/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"player1","password":"secret123"}'
```

List games:

```bash
curl http://localhost:8099/api/games
```

Place bet:

```bash
curl -X POST http://localhost:8099/api/bets \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1" \
  -d '{"gameId":"ultra-658","numbers":[5,12,23,34,41,55],"stake":20}'
```

## Frontend Integration

This backend is designed to work with the Expo frontend in the sibling workspace project:

- ../lottosimulator

Frontend API host/port should match this service, typically:

- host: localhost or LAN IP
- port: 8099

## Notes

- CORS is enabled for /api/** and currently allows all origins in development.
- Security is intentionally permissive for the current demo workflow, while passwords are still hashed using BCrypt.
- For production hardening, add strict auth, token-based session handling, environment-based secrets, and narrowed CORS rules.

## License

No explicit license file is currently defined in this repository.
