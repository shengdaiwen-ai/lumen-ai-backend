# Lumen AI Backend

## Project Introduction

Lumen AI Backend is an intelligent AI backend service built on Spring Boot, integrating the DeepSeek large language model. It provides comprehensive features including a conversation system, tool invocation, billing management, subscription services, and more. The system adopts a plugin-based tool architecture, supporting various AI tools such as code execution, file operations, web search, scheduled tasks, etc., allowing for flexible extension.

## Core Features

- **Intelligent Conversation System**: Based on the DeepSeek model, supports streaming output, context memory, and attachment processing.
- **Tool Calling Engine**: Built-in multiple tools (code execution, file system, web search, etc.), supports permission control.
- **Billing & Subscription**: Complete credit system, subscription plans, order management, pre-deduction mechanism.
- **Scheduled Tasks**: Supports creation, scheduling, and management of automated tasks.
- **Multi-channel Integration**: Supports bot integration for platforms like Feishu.
- **Reactive Architecture**: Implements SSE streaming response using WebFlux.

## Tech Stack

| Category | Technology |
|------|------|
| Framework | Spring Boot 3.x + Spring AI |
| Database | MySQL / PostgreSQL + MyBatis Plus |
| Authentication | JWT |
| Async | Project Reactor (WebFlux) |
| API | DeepSeek Chat API |
| Build | Maven |

## Project Structure

```
lumen-ai-backend/
├── src/main/java/com/lumenai/backend/
│   ├── LumenAiApplication.java          # Application Entry Point
│   ├── agent/                           # AI Core
│   │   ├── core/AgentEngine.java        # Agent Engine
│   │   └── tool/Tool.java               # Tool Interface
│   ├── auth/                            # Authentication Module
│   │   ├── JwtAuthFilter.java           # JWT Filter
│   │   ├── JwtUtil.java                 # JWT Utility
│   │   └── UserContext.java             # User Context
│   ├── config/                          # Configuration Classes
│   ├── controller/                      # REST API Controllers
│   │   ├── ChatController.java          # Conversation Interface
│   │   ├── AuthController.java          # Authentication Interface
│   │   ├── CreditController.java        # Credit Interface
│   │   ├── ScheduleController.java      # Scheduled Task Interface
│   │   ├── SessionController.java       # Session Management
│   │   ├── SubscriptionController.java  # Subscription Interface
│   │   └── WorkAssistantController.java # Work Assistant
│   ├── dto/                             # Data Transfer Objects
│   ├── entity/                          # Entity Classes
│   ├── mapper/                          # MyBatis Plus Mapper
│   ├── service/                         # Business Logic Layer
│   └── tool/                            # Tool Implementation
│       ├── agent/AgentTool.java         # Sub-agent
│       ├── automation/ScheduleTool.java # Scheduled Task
│       ├── code/ExecTool.java           # Code Execution
│       ├── filesystem/                  # File Operations
│       ├── interaction/                 # User Interaction
│       ├── shell/ShellTool.java         # Shell Commands
│       ├── time/DateTimeTool.java       # Date & Time
│       └── web/                         # Web Tools
├── src/main/resources/
│   ├── application.yml                  # Configuration File
│   ├── system-prompt.txt                # System Prompt
│   └── db/migration_*.sql               # Database Migration Scripts
└── pom.xml
```

## Quick Start

### Environment Requirements

- JDK 17+
- MySQL 8.0+ / PostgreSQL 14+
- Maven 3.8+

### Configuration Instructions

```yaml
# application.yml Core Configuration
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/lumen_ai
    username: root
    password: your_password
  ai:
    deepseek:
      chat:
        model: deepseek-chat
        api-key: ${DEEPSEEK_API_KEY}
        base-url: https://api.deepseek.com

lumen:
  jwt:
    secret: your-jwt-secret
    expire-seconds: 3600
    refresh-expire-seconds: 604800
  upload:
    dir: ${USER_HOME}/.lumen-ai/uploads
```

### Start Service

```bash
# Compile
mvn clean package -DskipTests

# Start
java -jar target/lumen-ai-backend.jar
```

## API Documentation

### Authentication Interfaces

| Method | Path | Description |
|------|------|------|
| POST | `/auth/register` | User Registration |
| POST | `/auth/login` | User Login |
| GET | `/auth/me` | Get Current User Info |
| POST | `/auth/refresh` | Refresh Access Token |

### Conversation Interfaces

| Method | Path | Description |
|------|------|------|
| POST | `/chat/stream` | Streaming Conversation (Returns SSE) |
| POST | `/chat/permission/{requestId}` | Handle Tool Permission Requests |
| POST | `/chat/question/{requestId}` | Reply to User Question |

### Session Management

| Method | Path | Description |
|------|------|------|
| GET | `/sessions` | Get Session List |
| POST | `/sessions` | Create Session |
| GET | `/sessions/{id}` | Get Session Details |
| GET | `/sessions/{id}/messages` | Get Message History |
| DELETE | `/sessions/{sessionId}/messages/{messageId}` | Delete Message |

### Credit Center

| Method | Path | Description |
|------|------|------|
| GET | `/credits/overview` | Credit Overview |
| GET | `/credits/rewards` | Credit Rewards List |
| GET | `/credits/usage` | Credit Usage Records |

### Scheduled Tasks

| Method | Path | Description |
|------|------|------|
| GET | `/schedules` | Task List |
| POST | `/schedules` | Create Task |
| POST | `/schedules/{id}/run` | Run Task Immediately |
| POST | `/schedules/{id}/toggle` | Toggle Status |

### Subscription Management

| Method | Path | Description |
|------|------|------|
| GET | `/subscription/plans` | Subscription Plans List |
| GET | `/subscription/preview` | Preview Subscription Cost |
| POST | `/subscription/orders` | Create Subscription Order |

### File Management

| Method | Path | Description |
|------|------|------|
| POST | `/files/upload` | Upload File |
| POST | `/files/upload-batch` | Batch Upload |
| GET | `/files/{key}` | Download File |

## AI Tools Description

The system includes various built-in AI tools that can be invoked via conversation:

| Tool Name | Function | Permission Requirement |
|----------|------|----------|
| `Agent` | Launch sub-agent to handle complex tasks | Requires Permission |
| `Exec` | Execute code (Python/JavaScript/Shell) | Needs Approval |
| `Shell` | Execute Shell commands | Needs Approval |
| `Read` | Read file content | None |
| `Write` | Create or overwrite file | Needs Approval |
| `Edit` | Edit file | Needs Approval |
| `Delete` | Delete file/directory | Needs Approval |
| `Glob` | Search files by pattern | None |
| `Grep` | Regular expression search file content | None |
| `WebSearch` | Web Search | None |
| `WebFetch` | Fetch webpage content | None |
| `DateTime` | Get current time | None |
| `createSchedule` | Create scheduled task | None |
| `listSchedules` | List scheduled tasks | None |
| `AskUserQuestion` | Ask user questions | None |

### Permission Modes

- **AUTO**: Automatically approve
- **ASK**: Ask user every time
- **REJECT**: Directly reject

## Billing Model

### Credit Calculation

Billing is based on token quantity, formula:

```
Cost = (Input Tokens × Input Price + Output Tokens × Output Price) × User Group Ratio
```

Cache hit tokens have discount privileges.

### Pre-deduction Mechanism

1. Pre-deduct charges before conversation starts (estimated)
2. Settle based on actual usage after conversation ends
3. Refund excess or charge deficit; stop if balance is insufficient

## Core Flow

### Conversation Request Flow

```
Client → POST /chat/stream 
      → Verify JWT Token
      → Check Credit Balance and Pre-deduct Charges
      → AgentEngine processes conversation
      → Stream result (SSE)
      → Settle and update credits
```

### Tool Invocation Flow

```
AI determines tool invocation needed
      → Check tool permissions
      → If insufficient permissions, send request to client
      → Continue execution after client confirmation
      → Tool returns result
      → AI synthesizes response
```

## Database Design

Main data tables:

- `users` - User Information
- `sessions` - Conversation Sessions
- `messages` - Message Records
- `schedules` - Scheduled Tasks
- `schedule_runs` - Task Execution Records
- `credit_accounts` - Credit Accounts
- `credit_transactions` - Credit Transactions
- `credit_holds` - Pre-deduction Records
- `user_subscriptions` - User Subscriptions
- `subscription_orders` - Subscription Orders
- `channel_accounts` - Channel Accounts (Feishu, etc.)

## Extension Development

### Add New Tool

```java
@Component
public class CustomTool implements Tool {
    @Override
    public String name() {
        return "custom_tool";
    }
    
    @Override
    public String description() {
        return "Custom Tool Description";
    }
    
    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        // Implement Logic
        return new ToolResult("Execution Result");
    }
}
```

### Add New API

```java
@RestController
@RequestMapping("/api/v1/custom")
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

This project adheres to the open-source license.

## Contact Information

- Project URL: https://gitee.com/daiwenshneg/lumen-ai-backend
- Issue Feedback: Please submit Issues on the Gitee repository