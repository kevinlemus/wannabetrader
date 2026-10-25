# WannabeTrader

All-in-one trading toolkit: trade journal, screener and backtester, connected to
Schwab/thinkorswim. Java Spring Boot + Angular + PostgreSQL.

## Tools

| Tool | Status |
|---|---|
| **Journal** - your trades, P/L, notes and stats | 🔨 building |
| **Screener** | planned |
| **Backtester** | planned |

## How it's built

One backend (a **modular monolith**) serves every tool. Each tool is its own module,
so it can be sold separately or split into its own service later.

```
backend/src/main/java/com/wannabetrader/
├── core/          shared foundation - every tool uses it
│   ├── auth/      Schwab login (OAuth), token kept fresh automatically
│   ├── account/   your Schwab accounts: list, select, set default
│   └── settings/  your saved choices
└── journal/       tool #1
```

Every module uses the same 4 layers: **model → dao → service → controller**.

Rules:
- Tools may use `core`. `core` never uses a tool.
- A tool never uses another tool directly.

## Stack

| Part | Tech |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Hibernate (Spring Data JPA) |
| Database | PostgreSQL 17 |
| Logging | Logback (SLF4J) → console + `backend/logs/` |
| Broker data | Schwab Trader API |
| Frontend | Angular (workspace: one app per tool + a shared library) |

## First-time setup

1. **Install** Java 21, Maven, PostgreSQL 17.
2. **Create the database:**
   ```sql
   CREATE ROLE wannabetrader LOGIN PASSWORD 'choose-a-password';
   CREATE DATABASE wannabetrader OWNER wannabetrader;
   ```
   Hibernate creates the tables by itself on first start.
3. **Private settings:** copy
   `backend/src/main/resources/application-local.properties.example` to
   `application-local.properties` in the same folder and fill it in
   (Schwab app key/secret, database password, certificate password).
   This file is git-ignored - never commit it.
4. **HTTPS certificate** (Schwab requires an `https://` callback). From `backend/`:
   ```
   keytool -genkeypair -alias wannabetrader -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore src/main/resources/ssl/keystore.p12 -validity 3650 -dname "CN=127.0.0.1" -ext "SAN=IP:127.0.0.1,DNS:localhost"
   ```
   Use the same password you put in `application-local.properties`.
5. **Schwab developer app:** its callback URL must be exactly
   `https://127.0.0.1:8080/api/auth/callback`.

## Run

From `backend/`:

```
mvn spring-boot:run
```

Then open `https://127.0.0.1:8080/api/auth/login` and sign in on Schwab's page
(the browser warns "Not secure" once because the certificate is self-made - click Continue).
The login lasts 7 days.

| Address | Does |
|---|---|
| `GET /api/auth/login` | Sign in to Schwab |
| `GET /api/accounts` | Your accounts (last 4 digits), default marked |
| `GET /api/accounts/{last4}` | Select one account |
| `PUT /api/accounts/default/{last4}` | Save your default account |

## Tests

From `backend/`:

```
mvn test
```

Tests use a fake Schwab and a throwaway in-memory database - they never touch real data.

## License

Proprietary - all rights reserved. See [LICENSE](LICENSE).
