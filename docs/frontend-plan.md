# Frontend plan (living document)

Updated as the backend grows. When it's time, this is the starting point for the
Claude Design prompt. Angular workspace: one app per tool (`journal`, later `screener`,
`backtester`) + a `shared` library (login status, account dropdown, styling).

## Design rules

- **Easy to scan.** Big numbers, short labels, tables and charts over paragraphs.
  (The main user is dyslexic - plain words, no walls of text.)
- **Green = profit, red = loss**, everywhere, the same way.
- **Money always to the cent**, times in New York time.
- Dark mode first (traders stare at it all day), light mode too.
- Every number explains itself on hover ("Profit factor: money won / money lost").
- Works on a laptop screen; a phone view for checking the day's results.

## Always on screen (top bar)

| Piece | Backend |
|---|---|
| Account dropdown (`****6943`), default pre-selected, "make default" | `GET /api/accounts`, `PUT /api/accounts/default/{last4}` |
| Schwab status: logged in until Oct 31 / "Log in again" button | `GET /api/auth/login` (status endpoint: to add) |
| Last import time + "Import now" | `POST /api/journal/import` |
| Period picker: Day / Week / Month / Year / All + date | `?period=&day=` on every page |

## Screens (journal)

### 1. Dashboard - "how am I doing?"
- Big tiles: **Total P/L**, **Win rate**, **Profit factor**, **Expectancy**, **Max drawdown**.
- Equity curve (running P/L) for the period.
- Calendar heat map: one square per day, green/red by day P/L, number of trades inside
  (from `DailyStats.byDay`). Tiles: **green-day %**, **avg green vs avg red day**, best/worst day,
  "red days caused by one trade".
- "Biggest leaks" card: the worst groups from the breakdowns
  (e.g. "Held overnight: -$6,072.98 on 55 trades").
- Backend: `GET /api/journal/stats` *(controller to add)*.

### 2. Trades - the list
- Table, newest first: date, time, symbol, Long/Short, shares, entry, exit, **hold time**,
  P/L, setup, review flag.
- Filters: symbol, side, setup, winners/losers, flagged only.
- Click a row -> Trade detail.
- Backend: `GET /api/journal/trades?period=&day=`.

### 3. Trade detail - "what happened?"
- **Chart image**: candles, volume, his indicators, arrows at every buy/sell. *(to build)*
- Fills table + reverse-split changes.
- Values at entry: gap %, % up at entry, float, RelVol, catalyst headline, VWAP/RSI/MACD... *(to build)*
- Rules check: each rule as a green/red chip with the real value ("Gap 34% ✓ ≥10%").
- Setup + notes (editable), mistake tags, planned stop -> R-multiple. *(tags/R to build)*
- Review flag banner when the app isn't sure.
- Backend: `GET /api/journal/trades/{id}`, `PUT /api/journal/trades/{id}/notes`.

### 4. Stats - "where do I make and lose money?"
- One section per breakdown, each a small bar chart (P/L per group) + a table
  (trades, win rate, P/L, expectancy):
  Side, Time of day, Day of week, Hold time, Price, Trade # of day, After a loss,
  Try on the same ticker, Size after a loss, Added to a loser.
  Later: gap %, float, RelVol, catalyst, indicator ranges, **halts** (entered right after a halt /
  held through a halt / no halt).
- Avg hold time: winners vs losers side by side.
- Backend: `GET /api/journal/stats/breakdown?by=` *(controller to add)*.

### 5. Rules - "my criteria"
- Set thresholds: gap ≥ 10%, price $2-$20, float < 20M, RelVol ≥ 5x, catalyst.
- Instantly shows: P/L when all rules were met vs when they weren't. *(to build)*

### 6. Settings
- Indicators and their settings, paste a thinkScript. *(to build)*
- Export for AI (one row per trade, every fact as a column). *(to build)*

## Real numbers to design with (Kevin's history, Jun 2021 - Oct 2026)

- 303 closed trades, 38.0% win rate, total -$6,805.68, profit factor 0.33.
- Held overnight: 55 trades, -$6,072.98. Added to a loser: 130 trades, -$5,031.80.
- Hold 15-60 min is the only positive hold-time group (+$139.65).
