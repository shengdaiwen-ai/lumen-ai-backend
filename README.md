

# Lumen AI Backend

Lumen AI 的服务端 —— 一个面向 AI 编程助手的 Agent 运行时与业务中台。

它把「一次提问」当作一条可观测、可计费、可中断的执行链来处理：请求进来后由 Agent 引擎驱动大模型完成多轮工具调用（文件读写/编辑/检索、Shell 与代码执行、联网搜索与抓取、定时任务、子 Agent、反问用户等 14 个内置工具），通过 SSE 把 token 增量、工具调用事件与用量统计实时推给客户端，最后按上游返回的真实 usage 结算积分。配套的订阅体系同样在服务端闭环 —— 套餐权益查库驱动、余额支付下单、升级差价折算、积分按月发放，客户端只消费服务端算好的结果。

> 桌面客户端见姊妹仓库：[lumen-ai-frontend](https://gitee.com/daiwenshneg/lumen-ai-frontend)

## 核心特性

- **Agent 引擎**：单引擎驱动多轮「思考 → 工具调用 → 观察」循环，SSE 实时推送，工具写入/执行类操作按权限档位触发调用前审批
- **两阶段积分扣费**：预扣（hold）→ 结算（settle）→ 退款（refund），支持模型单价、分组倍率、缓存命中折价，超时预扣单惰性清扫
- **订阅与余额**：套餐/权益/价格全部查库驱动，余额原子扣款下单、升级差价折算（防套利）、积分按月发放与回收，全链路留痕
- **会话与附件**：会话、消息、附件落库，逻辑删除
- **办公助理通道**：微信（iLink）机器人消息接入，与 Web 聊天共用同一套计费引擎
- **启动期幂等迁移**：建表、补列、数据回填由 `DbMigrationRunner` 自动完成，无需手工执行 SQL
- **定时任务**：Quartz 调度，支持创建、立即执行、启停

## 技术栈

| 类别 | 技术 |
|------|------|
| 框架 | Spring Boot 4.1.1 · Java 17 |
| AI | Spring AI 2.0.1（DeepSeek 模型） |
| 持久层 | MyBatis-Plus 3.5.17（`LambdaQueryWrapper`，零 XML） |
| 数据库 | MySQL 8.0（utf8mb4） |
| 缓存 | Redis |
| 认证 | 自研 JWT（jjwt 0.12.6）+ BCrypt |
| 调度 | Quartz |
| 流式 | WebFlux SSE |
| 文档 | SpringDoc OpenAPI（Swagger UI） |
| 构建 | Maven |

## 项目结构

```
lumen-ai-backend/
├── src/main/java/com/lumenai/backend/
│   ├── LumenAiApplication.java          # 启动类
│   ├── agent/                           # AI 核心
│   │   ├── core/AgentEngine.java        # Agent 引擎（多轮工具循环 + usage 统计）
│   │   └── tool/Tool.java               # 工具接口
│   ├── auth/                            # 认证模块（JWT 过滤器 / 工具 / 用户上下文）
│   ├── config/                          # 配置类（含 DbMigrationRunner 幂等迁移）
│   ├── controller/                      # REST 控制器（统一响应 R<T>）
│   ├── dto/ / entity/ / mapper/         # 传输对象 / 实体 / Mapper
│   ├── service/                         # 业务逻辑（含 ChatBillingService 计费引擎）
│   └── tool/                            # 内置工具实现
│       ├── agent/                       # 子智能体
│       ├── automation/                  # 定时任务
│       ├── code/ExecTool.java           # 代码执行
│       ├── filesystem/                  # 文件读写/编辑/删除/检索
│       ├── interaction/                 # 反问用户
│       ├── shell/ShellTool.java         # Shell 命令
│       ├── time/                        # 日期时间
│       └── web/                         # 网络搜索与网页抓取
├── src/main/resources/
│   ├── application.yml                  # 主配置（默认激活 local profile）
│   ├── system-prompt.txt                # 系统提示词
│   └── db/                              # 建表与迁移脚本（由迁移器执行）
└── pom.xml
```

## 快速开始

### 环境要求

- JDK 17+
- MySQL 8.0+
- Redis
- Maven 3.8+

### 配置说明

真实凭据**不入库**：默认激活 `local` profile，从 gitignore 的 `application-local.yml` 读取；也可全部用环境变量注入：

| 环境变量 | 说明 |
|------|------|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | MySQL 连接（库名示例 `lumen_ai`） |
| `DEEPSEEK_API_KEY` | DeepSeek API Key |
| `JWT_SECRET` | JWT 签名密钥（生产环境必须覆盖为随机串） |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接 |
| `BOCHA_API_KEY` | 博查搜索 Key（可选，未配置时降级为爬虫搜索） |
| `SPRING_PROFILES_ACTIVE` | 生产环境设为非 `local` 值 |

核心配置（`application.yml` 已内置默认值）：

```yaml
server:
  port: 9031                # 服务端口
  servlet:
    context-path: /api      # 所有接口带 /api 前缀

spring:
  ai:
    deepseek:
      base-url: https://api.deepseek.com
      chat:
        model: deepseek-v4-pro
```

### 启动服务

```bash
# 编译打包
mvn clean package -DskipTests

# 启动（默认 9031 端口）
java -jar target/lumen-ai-backend-0.1.0.jar
```

启动后访问 Swagger 文档：`http://localhost:9031/api/swagger-ui.html`

## API 概览

所有接口前缀均为 `/api`。完整定义见 Swagger 文档，常用接口如下：

| 模块 | 方法与路径 | 说明 |
|------|------|------|
| 认证 | `POST /auth/register` · `POST /auth/login` · `GET /auth/me` · `POST /auth/refresh` | 注册 / 登录 / 当前用户 / 刷新令牌 |
| 对话 | `POST /chat/stream`（SSE）· `POST /chat/permission/{requestId}` · `POST /chat/question/{requestId}` | 流式对话 / 工具审批 / 回复提问 |
| 会话 | `GET·POST /sessions` · `GET /sessions/{id}/messages` · `DELETE /sessions/{sessionId}/messages/{messageId}` | 会话与消息管理 |
| 积分 | `GET /credits/overview` · `GET /credits/usage` · `GET /credits/rewards` | 积分概览 / 流水 / 奖励 |
| 订阅 | `GET /subscription/plans` · `GET /subscription/preview` · `POST /subscription/orders` | 套餐 / 升级折算预览 / 余额支付下单 |
| 余额 | `GET /balance/...` | 余额账户与充值流水 |
| 定时任务 | `GET·POST /schedules` · `POST /schedules/{id}/run` · `POST /schedules/{id}/toggle` | 任务管理 |
| 文件 | `POST /files/upload` · `POST /files/upload-batch` · `GET /files/{key}` | 附件上传下载 |
| 工作助手 | `/work-assistant/...` | 微信（iLink）机器人绑定与消息 |

## 内置工具

| 工具 | 功能 | 审批 |
|------|------|------|
| `Read` / `Glob` / `Grep` | 读文件 / 按模式查找 / 正则搜索 | 免审批 |
| `WebSearch` / `WebFetch` | 联网搜索 / 抓取网页 | 免审批 |
| `DateTime` / `createSchedule` / `listSchedules` | 时间 / 定时任务管理 | 免审批 |
| `AskUserQuestion` | 反问用户 | 免审批 |
| `Write` / `Edit` / `Delete` | 写 / 改 / 删文件 | 按权限档位审批 |
| `Exec` / `Shell` | 执行代码 / Shell 命令 | 按权限档位审批 |
| `Agent` | 启动子智能体处理复杂任务 | 按权限档位审批 |

用户权限分三档（**只读 / 智能 / 全自动**），写入与执行类工具按档位决定是否在客户端弹出审批确认。

## 计费模型

### 两阶段扣费

```
发送前  预检余额 → 预扣积分（按待发文本与模型上限估额）→ 写入 credit_holds
done    按上游真实 usage 结算：held → settled，多退少补
error   全额退款
超时    held 超过 10 分钟由惰性清扫退款（挂在高频读接口上，无定时任务）
```

### 积分公式

```
费用 = 缓存命中tokens × 输入单价 × 缓存折价
     + 缓存未命中tokens × 输入单价
     + 输出tokens × 输出单价
     × 分组倍率
```

模型单价（`model_pricing`）与分组倍率（`credit_group_ratio`）均查库配置；免费模型（`is_free=1`）全程零成本。

### 订阅

- 套餐、权益、价格全部存表（`subscription_plans` 系列），前端展示与判断均由服务端推导
- 余额支付下单为单事务闭环：校验 → 原子扣款 → 订单快照 → 订阅变更 → 发放/回收积分
- 升级按剩余价值折算差价，积分按月发放（`subscription_credit_grants`，懒补发）

## 核心流程

```
客户端 → POST /api/chat/stream
      → JWT 鉴权 → 积分预检与预扣（hold）
      → AgentEngine 多轮循环（工具调用按权限审批）
      → SSE 实时返回 token 增量与工具事件
      → done：按真实 usage 结算（settle）
```

## 主要数据表

| 分类 | 表 |
|------|------|
| 用户 | `users` |
| 对话 | `sessions` · `messages` · `attachments` |
| 定时任务 | `schedules` · `schedule_runs` |
| 积分 | `credit_accounts` · `credit_transactions` · `credit_holds` · `credit_rewards` · `credit_group_ratio` |
| 计价 | `model_pricing` |
| 订阅 | `subscription_plans` / `_features` / `_billings` · `user_subscriptions` · `subscription_orders` · `subscription_credit_grants` |
| 余额 | `balance_transactions` |
| 渠道 | `channel_accounts` · `channel_messages` |

## 扩展开发

### 新增工具

实现 `Tool` 接口并注册为 Spring Bean，即可被 Agent 引擎自动发现：

```java
@Component
public class CustomTool implements Tool {
    @Override
    public String name() { return "custom_tool"; }

    @Override
    public String description() { return "自定义工具描述"; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        return new ToolResult("执行结果");
    }
}
```

### 新增接口

Controller 只做参数校验与转发，业务放 Service，统一返回 `R<T>`；新增受保护接口需在 `JwtAuthFilter` 的鉴权清单中登记：

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

本项目基于 [MIT License](LICENSE) 开源。

## 联系方式

- 项目地址：https://gitee.com/daiwenshneg/lumen-ai-backend
- 问题反馈：请在 Gitee 仓库提交 [Issue](https://gitee.com/daiwenshneg/lumen-ai-backend/issues)
