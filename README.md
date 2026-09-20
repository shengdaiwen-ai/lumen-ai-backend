

# Lumen AI Backend

## 项目简介

Lumen AI Backend 是一个基于 Spring Boot 构建的智能 AI 后端服务，集成了 DeepSeek 大语言模型，提供完整的对话系统、工具调用、计费管理、订阅服务等功能。系统采用插件化的工具架构，支持代码执行、文件操作、网络搜索、定时任务等多种 AI 工具，可灵活扩展。

## 核心特性

- **智能对话系统**：基于 DeepSeek 模型，支持流式输出、上下文记忆、附件处理
- **工具调用引擎**：内置多种工具（代码执行、文件系统、Web搜索等），支持权限控制
- **计费与订阅**：完整的积分体系、订阅计划、订单管理、预扣费机制
- **定时任务**：支持创建、调度、管理自动化任务
- **多渠道集成**：支持飞书等平台的机器人集成
- **响应式架构**：采用 WebFlux 实现 SSE 流式响应

## 技术栈

| 类别 | 技术 |
|------|------|
| 框架 | Spring Boot 3.x + Spring AI |
| 数据库 | MySQL / PostgreSQL + MyBatis Plus |
| 认证 | JWT |
| 异步 | Project Reactor (WebFlux) |
| API | DeepSeek Chat API |
| 构建 | Maven |

## 项目结构

```
lumen-ai-backend/
├── src/main/java/com/lumenai/backend/
│   ├── LumenAiApplication.java          # 启动类
│   ├── agent/                           # AI 核心
│   │   ├── core/AgentEngine.java        # 智能体引擎
│   │   └── tool/Tool.java               # 工具接口
│   ├── auth/                            # 认证模块
│   │   ├── JwtAuthFilter.java           # JWT 过滤器
│   │   ├── JwtUtil.java                 # JWT 工具
│   │   └── UserContext.java             # 用户上下文
│   ├── config/                          # 配置类
│   ├── controller/                      # REST API 控制器
│   │   ├── ChatController.java          # 对话接口
│   │   ├── AuthController.java          # 认证接口
│   │   ├── CreditController.java        # 积分接口
│   │   ├── ScheduleController.java      # 定时任务接口
│   │   ├── SessionController.java       # 会话管理
│   │   ├── SubscriptionController.java  # 订阅接口
│   │   └── WorkAssistantController.java # 工作助手
│   ├── dto/                             # 数据传输对象
│   ├── entity/                          # 实体类
│   ├── mapper/                          # MyBatis Plus Mapper
│   ├── service/                         # 业务逻辑层
│   └── tool/                            # 工具实现
│       ├── agent/AgentTool.java         # 子智能体
│       ├── automation/ScheduleTool.java # 定时任务
│       ├── code/ExecTool.java           # 代码执行
│       ├── filesystem/                  # 文件操作
│       ├── interaction/                 # 用户交互
│       ├── shell/ShellTool.java         # Shell 命令
│       ├── time/DateTimeTool.java       # 日期时间
│       └── web/                         # Web 工具
├── src/main/resources/
│   ├── application.yml                  # 配置文件
│   ├── system-prompt.txt                # 系统提示词
│   └── db/migration_*.sql               # 数据库迁移脚本
└── pom.xml
```

## 快速开始

### 环境要求

- JDK 17+
- MySQL 8.0+ / PostgreSQL 14+
- Maven 3.8+

### 配置说明

```yaml
# application.yml 核心配置
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

### 启动服务

```bash
# 编译
mvn clean package -DskipTests

# 启动
java -jar target/lumen-ai-backend.jar
```

## API 文档

### 认证接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/auth/register` | 用户注册 |
| POST | `/auth/login` | 用户登录 |
| GET | `/auth/me` | 获取当前用户信息 |
| POST | `/auth/refresh` | 刷新 Access Token |

### 对话接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/chat/stream` | 流式对话（返回 SSE） |
| POST | `/chat/permission/{requestId}` | 处理工具权限请求 |
| POST | `/chat/question/{requestId}` | 回复用户问题 |

### 会话管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/sessions` | 获取会话列表 |
| POST | `/sessions` | 创建会话 |
| GET | `/sessions/{id}` | 获取会话详情 |
| GET | `/sessions/{id}/messages` | 获取消息历史 |
| DELETE | `/sessions/{sessionId}/messages/{messageId}` | 删除消息 |

### 积分中心

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/credits/overview` | 积分概览 |
| GET | `/credits/rewards` | 积分奖励列表 |
| GET | `/credits/usage` | 积分使用记录 |

### 定时任务

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/schedules` | 任务列表 |
| POST | `/schedules` | 创建任务 |
| POST | `/schedules/{id}/run` | 立即执行任务 |
| POST | `/schedules/{id}/toggle` | 切换状态 |

### 订阅管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/subscription/plans` | 订阅计划列表 |
| GET | `/subscription/preview` | 预览订阅费用 |
| POST | `/subscription/orders` | 创建订阅订单 |

### 文件管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/files/upload` | 上传文件 |
| POST | `/files/upload-batch` | 批量上传 |
| GET | `/files/{key}` | 下载文件 |

## AI 工具说明

系统内置多种 AI 工具，可通过对话调用：

| 工具名称 | 功能 | 权限要求 |
|----------|------|----------|
| `Agent` | 启动子智能体处理复杂任务 | 需要权限 |
| `Exec` | 执行代码（Python/JavaScript/Shell） | 需审批 |
| `Shell` | 执行 Shell 命令 | 需审批 |
| `Read` | 读取文件内容 | 无 |
| `Write` | 创建或覆盖文件 | 需审批 |
| `Edit` | 编辑文件 | 需审批 |
| `Delete` | 删除文件/目录 | 需审批 |
| `Glob` | 按模式查找文件 | 无 |
| `Grep` | 正则搜索文件内容 | 无 |
| `WebSearch` | 网络搜索 | 无 |
| `WebFetch` | 获取网页内容 | 无 |
| `DateTime` | 获取当前时间 | 无 |
| `createSchedule` | 创建定时任务 | 无 |
| `listSchedules` | 列出定时任务 | 无 |
| `AskUserQuestion` | 向用户提问 | 无 |

### 权限模式

- **AUTO**：自动批准
- **ASK**：每次询问用户
- **REJECT**：直接拒绝

## 计费模型

### 积分计算

计费基于 token 数量，公式：

```
费用 = (输入token数 × 输入单价 + 输出token数 × 输出单价) × 用户分组比例
```

缓存命中 token 有折扣优惠。

### 预扣费机制

1. 对话开始前预扣费用（预估）
2. 对话结束后根据实际用量结算
3. 多退少补，余额不足时禁止继续

## 核心流程

### 对话请求流程

```
客户端 → POST /chat/stream 
      → 验证 JWT Token
      → 检查积分余额并预扣费
      → AgentEngine 处理对话
      → 流式返回结果（SSE）
      → 结算并更新积分
```

### 工具调用流程

```
AI 判断需要调用工具
      → 检查工具权限
      → 权限不足时发送请求到客户端
      → 客户端确认后继续执行
      → 工具返回结果
      → AI 整合回复
```

## 数据库设计

主要数据表：

- `users` - 用户信息
- `sessions` - 对话会话
- `messages` - 消息记录
- `schedules` - 定时任务
- `schedule_runs` - 任务执行记录
- `credit_accounts` - 积分账户
- `credit_transactions` - 积分交易
- `credit_holds` - 预扣费记录
- `user_subscriptions` - 用户订阅
- `subscription_orders` - 订阅订单
- `channel_accounts` - 渠道账户（飞书等）

## 扩展开发

### 新增工具

```java
@Component
public class CustomTool implements Tool {
    @Override
    public String name() {
        return "custom_tool";
    }
    
    @Override
    public String description() {
        return "自定义工具描述";
    }
    
    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        // 实现逻辑
        return new ToolResult("执行结果");
    }
}
```

### 新增 API

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

本项目遵循开源协议。

## 联系方式

- 项目地址：https://gitee.com/daiwenshneg/lumen-ai-backend
- 问题反馈：请在 Gitee 仓库提交 Issue