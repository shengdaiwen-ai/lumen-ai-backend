

# Lumen AI Backend

The server side of Lumen AI — an agent runtime and business platform for an AI coding assistant.

It treats every question as an observable, billable, interruptible execution chain: the Agent engine drives the LLM through multi-round tool calling (file read/write/edit/search, shell and code execution, web search & fetch, scheduled tasks, sub-agents, ask-the-user, etc. — 14 built-in tools), streams token deltas, tool events and usage stats to the client via SSE, and finally settles credits based on the real usage returned by the upstream model. The subscription system is equally closed-loop on the server: plan/entitlement data is database-driven, balance-paid ordering, upgrade proration and monthly credit grants are all computed server-side — the client only consumes the results.

> Desktop client: the companion repo [lumen-ai-frontend](https://gitee.com/daiwenshneg/lumen-ai-frontend).

## Core Features

- **Agent Engine**: drives multi-round "think → tool call → observe" loops, real-time SSE streaming, and pre-call approval for write/execute tools based on permission tiers
- **Two-phase Credit Billing**: hold → settle → refund, with model pricing, group ratios and cache-hit discounts; expired holds are lazily swept
- **Subscription & Balance**: plans/entitlements/prices all stored in tables; atomic balance deduction, upgrade proration (arbitrage-proof), monthly credit grants & reclaim, full audit trail
- **Sessions & Attachments**: sessions, messages and attachments persisted with logical delete
- **Work Assistant Channel**: WeChat (iLink) bot integration, sharing the same billing engine as web chat
- **Idempotent Startup Migration**: table creation, column addition and data backfill run automatically via `DbMigrationRunner` — no manual SQL needed
- **Scheduled Tasks**: Quartz-powered create / run-now / toggle

## Tech Stack

| Category | Technology |
|------|------|
| Framework | Spring Boot 4.1.1 · Java 17 |
| AI | Spring AI 2.0.1 (DeepSeek model) |
| Persistence | MyBatis-Plus 3.5.17 (`LambdaQueryWrapper`, zero XML) |
| Database | MySQL 8.0 (utf8mb4) |
| Cache | Redis |
| Auth | Self-built JWT (jjwt 0.12.6) + BCrypt |
| Scheduling | Quartz |
| Streaming | WebFlux SSE |
| Docs | SpringDoc OpenAPI (Swagger UI) |
| Build | Maven |

## Project Structure

```
lumen-ai-backend/
├── src/main/java/com/lumenai/backend/
│   ├── LumenAiApplication.java          # Application entry point
│   ├── agent/                           # AI core
│   │   ├── core/AgentEngine.java        # Agent engine (multi-round tool loop + usage stats)
│   │   └── tool/Tool.java               # Tool interface
│   ├── auth/                            # Authentication (JWT filter / utils / user context)
│   ├── config/                          # Configuration (incl. DbMigrationRunner)
│   ├── controller/                      # REST controllers (unified R<T> response)
│   ├── dto/ / entity/ / mapper/         # DTOs / entities / mappers
│   ├── service/                         # Business logic (incl. ChatBillingService)
│   └── tool/                            # Built-in tools
│       ├── agent/                       # Sub-agent
│       ├── automation/                  # Scheduled tasks
│       ├── code/ExecTool.java           # Code execution
│       ├── filesystem/                  # File read/write/edit/delete/search
│       ├── interaction/                 # Ask the user
│       ├── shell/ShellTool.java         # Shell commands
│       ├── time/                        # Date & time
│       └── web/                         # Web search & fetch
├── src/main/resources/
│   ├── application.yml                  # Main config (activates local profile by default)
│   ├── system-prompt.txt                # System prompt
│   └── db/                              # Schema & migration scripts (run by the migrator)
└── pom.xml
```

## Quick Start

### Requirements

- JDK 17+
- MySQL 8.0+
- Redis
- Maven 3.8+

### Configuration

Real credentials are **never committed**: the `local` profile is active by default and reads from the gitignored `application-local.yml`; everything can also be injected via environment variables:

| Environment Variable | Description |
|------|------|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | MySQL connection (database e.g. `lumen_ai`) |
| `DEEPSEEK_API_KEY` | DeepSeek API key |
| `JWT_SECRET` | JWT signing secret (must be overridden in production) |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis connection |
| `BOCHA_API_KEY` | Bocha search key (optional; falls back to crawler search) |
| `SPRING_PROFILES_ACTIVE` | Set to a non-`local` value in production |

Key settings (defaults already in `application.yml`):

```yaml
server:
  port: 9031                # service port
  servlet:
    context-path: /api      # all endpoints are prefixed with /api

spring:
  ai:
    deepseek:
      base-url: https://api.deepseek.com
      chat:
        model: deepseek-v4-pro
```

### Run

```bash
# Build
mvn clean package -DskipTests

# Start (port 9031 by default)
java -jar target/lumen-ai-backend-0.1.0.jar
```

Swagger UI: `http://localhost:9031/api/swagger-ui.html`

## API Overview

All endpoints are prefixed with `/api`. See Swagger UI for the full reference; the most-used ones:

| Module | Method & Path | Description |
|------|------|------|
| Auth | `POST /auth/register` · `POST /auth/login` · `GET /auth/me` · `POST /auth/refresh` | Register / login / current user / refresh token |
| Chat | `POST /chat/stream` (SSE) · `POST /chat/permission/{requestId}` · `POST /chat/question/{requestId}` | Streaming chat / tool approval / answer question |
| Sessions | `GET·POST /sessions` · `GET /sessions/{id}/messages` · `DELETE /sessions/{sessionId}/messages/{messageId}` | Session & message management |
| Credits | `GET /credits/overview` · `GET /credits/usage` · `GET /credits/rewards` | Credit overview / usage / rewards |
| Subscription | `GET /subscription/plans` · `GET /subscription/preview` · `POST /subscription/orders` | Plans / upgrade quote / balance-paid order |
| Balance | `GET /balance/...` | Balance account & recharge transactions |
| Schedules | `GET·POST /schedules` · `POST /schedules/{id}/run` · `POST /schedules/{id}/toggle` | Task management |
| Files | `POST /files/upload` · `POST /files/upload-batch` · `GET /files/{key}` | Attachment upload & download |
| Work Assistant | `/work-assistant/...` | WeChat (iLink) bot binding & messaging |

## Built-in Tools

| Tool | Function | Approval |
|------|------|------|
| `Read` / `Glob` / `Grep` | Read files / find by pattern / regex search | No approval |
| `WebSearch` / `WebFetch` | Web search / fetch pages | No approval |
| `DateTime` / `createSchedule` / `listSchedules` | Time / scheduled task management | No approval |
| `AskUserQuestion` | Ask the user | No approval |
| `Write` / `Edit` / `Delete` | Write / edit / delete files | Approval by permission tier |
| `Exec` / `Shell` | Run code / shell commands | Approval by permission tier |
| `Agent` | Launch a sub-agent for complex tasks | Approval by permission tier |

User permissions have three tiers (**read-only / smart / full-auto**); write & execute tools trigger a client-side approval dialog depending on the tier.

## Billing Model

### Two-phase billing

```
Before send   check balance → hold credits (estimated) → write credit_holds
done          settle with real upstream usage: held → settled, refund or top-up
error         full refund
expired       holds older than 10 min are lazily refunded (hooked on hot read paths, no cron)
```

### Credit formula

```
cost = cache-hit tokens × input price × cache ratio
     + cache-miss tokens × input price
     + output tokens × output price
     × group ratio
```

Model pricing (`model_pricing`) and group ratios (`credit_group_ratio`) are database-configured; free models (`is_free=1`) cost nothing.

### Subscription

- Plans, entitlements and prices all live in tables (`subscription_plans` family); all display flags are derived server-side
- Balance-paid ordering is a single transaction: validate → atomic deduct → order snapshot → subscription change → grant/reclaim credits
- Upgrades prorate remaining value; credits are granted monthly (`subscription_credit_grants`, lazy catch-up)

## Core Flow

```
Client → POST /api/chat/stream
      → JWT auth → credit check & hold
      → AgentEngine loop (tool calls approved by tier)
      → SSE: token deltas & tool events in real time
      → done: settle with real usage
```

## Main Tables

| Category | Tables |
|------|------|
| Users | `users` |
| Chat | `sessions` · `messages` · `attachments` |
| Schedules | `schedules` · `schedule_runs` |
| Credits | `credit_accounts` · `credit_transactions` · `credit_holds` · `credit_rewards` · `credit_group_ratio` |
| Pricing | `model_pricing` |
| Subscription | `subscription_plans` / `_features` / `_billings` · `user_subscriptions` · `subscription_orders` · `subscription_credit_grants` |
| Balance | `balance_transactions` |
| Channels | `channel_accounts` · `channel_messages` |

## Extension

### Add a tool

Implement the `Tool` interface and register it as a Spring bean — the Agent engine discovers it automatically:

```java
@Component
public class CustomTool implements Tool {
    @Override
    public String name() { return "custom_tool"; }

    @Override
    public String description() { return "Custom tool description"; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        return new ToolResult("result");
    }
}
```

### Add an endpoint

Controllers only validate and delegate; business logic lives in services; responses use the unified `R<T>`. New protected endpoints must be registered in `JwtAuthFilter`'s auth list:

```java
@RestController
@RequestMapping("/custom")
@RequiredArgsConstructor
public class CustomController {
    private final CustomService service;

    @GetMapping("/data")
    public R<DataVO> getData() {
        return R.ok(service.getData());
    }
}
```

## License

Released under the [MIT License](LICENSE).

## Contact

- Repository: https://gitee.com/daiwenshneg/lumen-ai-backend
- Feedback: please open an [Issue](https://gitee.com/daiwenshneg/lumen-ai-backend/issues) on Gitee
