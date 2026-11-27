# cordis-java

[cordis](https://github.com/cordiverse/cordis)架构的 **Java 8 实现**，并在此基础上构建一套可运行的 agent 基础设施：文件系统、SQLite、HTTP、SSE、技能（skill）、无头浏览器、模型工具调用循环，以及可直接启动服务的 `AgentServer`。

- 语言差异的处理方式：TypeScript 依靠 Proxy 隐式解析服务，本实现改为显式 `ctx.get(name)`；TypeScript 以原生 Promise 表达异步，本实现提供绑定主循环的 `Promise` / `Deferred`。
- 项目目标不是语法层面的翻译，而是完整保留 cordis 的**调度模型与生命周期语义**：主循环串行决策、阻塞任务 offload、依赖驱动挂载、effect 逆序回收、热插拔。
- 规模：107 个 Java 文件、约 1.5 万行；不依赖任何应用框架，第三方库共 6 个（见 [依赖](#依赖与版本选择)）。

---

## 目录

- [特性一览](#特性一览)
- [环境与构建](#环境与构建)
- [快速开始](#快速开始)
- [架构](#架构)
- [内核：cordis.core](#内核cordiscore)
- [能力层](#能力层)
- [Agent 层](#agent-层)
- [适配层与工具清单](#适配层与工具清单)
- [应用：AgentServer / AgentCli](#应用agentserver--agentcli)
- [线程模型与编码约定](#线程模型与编码约定)
- [日志](#日志)
- [安全模型](#安全模型)
- [验证与回归](#验证与回归)
- [已知限制](#已知限制)
- [目录结构与阅读顺序](#目录结构与阅读顺序)
- [依赖与版本选择](#依赖与版本选择)

---

## 特性一览

| 能力 | 实现要点 |
|---|---|
| 依赖注入 / 插件生命周期 | `Component` → `Fiber`（6 态状态机）→ `Registry`；依赖用 `inject` 声明，服务出现/消失自动挂载/卸载 |
| 异步调度 | 单线程主循环 + 扩展阻塞池（对应 JS 主线程 + libuv 线程池）；框架内部状态无锁 |
| 一等 Promise | 状态迁移与回调全在主循环，`fulfill/reject` 允许任意线程；`Deferred` 分离生产/消费 |
| 事件系统 | `emit / serial / bail / waterfall / parallel` 五种派发；监听器返回 Promise 自动进入异步链路；随 fiber 自动解绑 |
| 多租户隔离 | `ctx.isolate(name)` 生成隔离标签，同名服务在分支间互不可见 |
| 文件系统 | 沙箱（词法 + 真实路径双校验）、写前审批、大小上限、原子写、grep |
| SQLite | 两段式异步初始化（建连 offload → 主循环登记）、事务、参数化查询 |
| HTTP / SSE | OkHttp `enqueue` 桥接 Promise；增量 SSE 解析；重定向逐跳白名单校验 |
| 技能 | 目录扫描 → 清单注入 system prompt、正文按窗口续读（控制 token 占用） |
| 无头浏览器 | Playwright 1.65 + 无障碍树 `[ref=eN]` + `getByRef`，模型无需编写 CSS 选择器 |
| Agent loop | 多轮工具调用、工具并发池、工具热插拔、可取消、事件流 |
| 记忆 | SQLite 持久化、`seq` 数据库侧原子分配、轮次事务、残缺配对自修复 |
| 服务化 | `AgentServer`（HTTP + SSE）+ `AgentCli`（控制台客户端）；默认只读、不开放网络、不启用浏览器 |

---

## 环境与构建

- **JDK 8+**（`maven.compiler.source/target = 1.8`，全部代码可在 JDK 8 编译）
- **Maven 3.6+**

```bash
# 编译
mvn compile

# 打 fat jar（可执行，一个 jar 走 server / cli 两个入口）
mvn package -DskipTests
# → target/cordis-java-1.0.0-SNAPSHOT.jar（约 14MB）

# 生成运行期 classpath（classpath 方式启动时使用）
mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt

# 统一引用方式：
CP="target/classes:$(cat target/cp.txt)"
```

> 依赖已存在于本地仓库时，可追加 `-o` 使用离线解析以缩短构建时间；首次拉取（尤其 218MB 的 Playwright 驱动）需要联网。

> Playwright 引入 **218MB** 的 `driver-bundle` jar（229 MB 十进制），首次依赖解析耗时明显增加；该依赖仅服务于 `cordis.browser`，内核不参与。

> fat jar 有意排除了 driver-bundle：`java -jar` 启动的服务里 `--enable-browser` 不可用（其余能力自包含）；需要浏览器时用 `$CP` classpath 方式启动。

---

## 快速开始

### 1. 运行最小生命周期示例

```bash
java -cp "$CP" cordis.demo.LifecycleDemo
```

示例覆盖：根上下文；慢初始化插件通过 `applyAsync` offload；fiber 停留在 `LOADING` 期间主循环仍可挂载其他插件；`Promise.all` 等待全部就绪；卸载时 effect 逆序回收。

### 2. 启动可对话的 agent 服务

凭证仅从环境变量读取（OpenAI 兼容；换成 vLLM / Ollama / DeepSeek 等兼容端点时只改 base 地址）：

```bash
export CORDIS_JAVA_APP_ID=<你的 OpenAI API Key>   # 必填（作为 Bearer Token 发送）
export CORDIS_JAVA_BASE_URL=https://api.openai.com/v1    # 必填（填到 /v1 这一级即可）
export CORDIS_JAVA_MODEL=gpt-4o                 # 可选，不设用默认模型

# fat jar 方式（推荐）：一个 jar 同时是两个入口的启动器
java -jar target/cordis-java-1.0.0-SNAPSHOT.jar server --port 8787 --root ~/agent-ws --allow-write

# 或 classpath 方式
java -cp "$CP" cordis.app.AgentServer --port 8787 --root ~/agent-ws --allow-write
```

在另一个终端启动客户端：

```bash 
java -jar target/cordis-java-1.0.0-SNAPSHOT.jar cli   # 默认使用 SSE，逐轮输出
# 或 classpath 方式：
java -cp "$CP" cordis.app.AgentCli
```

也可直接使用 curl：

```bash
curl -s -X POST http://127.0.0.1:8787/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"看一下工作区有哪些文件"}' | python3 -m json.tool
```

### 3. 在自有代码中装配

```java
Context root = Context.root();   // 内置 Scheduler：cordis-main-loop + cordis-worker

// 能力：SQLite + 文件系统（写操作默认拒绝，须显式提供 ApprovalGate）
root.registry.use(SqlitePlugin.INSTANCE, Maps.of("url", "jdbc:sqlite::memory:"), null);
root.registry.use(FileSystemPlugin.INSTANCE, Maps.of(
        "root", "/tmp/agent-ws",
        "approval", (ApprovalGate) request -> true), null);   // 生产环境应替换为真实审批实现

// agent：需要一个 LlmClient 实现
root.registry.use(AgentPlugin.INSTANCE, Maps.of(
        "llm", new OpenAiLlmClient(...),
        "maxSteps", 8,
        "system", "你是一个谨慎的助手"), null);

// 工具：每个工具对应一个 fiber，dispose 即下线
root.registry.use(FileSystemTools.INSTANCE, Maps.of(),
        Maps.of("filesystem", true, "agent.tools", true));
root.registry.use(new ToolComponent(new MyTool()), Maps.of(),
        Maps.of("agent.tools", true));                        // 自定义工具采用相同方式

// 全部就绪后直接运行 loop
AgentService agent = root.get("agent");
AgentResult result = agent.run(Collections.emptyList(), "读一下 README.md").completion().join();
```

> `cordis.agent.fs` 中的具体工具类（`ReadFileTool` 等）为包私有，统一由
> `FileSystemTools` 注册；新增自定义工具时，实现 `Tool` 并由 `ToolComponent` 包装。

---

## 架构

三层结构，依赖方向单一，下层不感知上层：

```mermaid
graph TD
  App["cordis.app<br/>AgentServer · AgentCli · WorkspaceInitializer"]
  Adapter["cordis.agent.*（适配层）<br/>fs / http / browser / skill 工具 · llm wire"]
  Agent["cordis.agent（编排层）<br/>loop · ToolService · Memory · Conversation"]
  Cap["cordis.sqlite · http · fs · skill · browser（能力层）<br/>只提供能力，不认识 agent，不返回给模型看的 JSON"]
  Core["cordis.core（内核）<br/>Context · Fiber · Registry · Scheduler · Promise · Events"]

  App --> Adapter
  App --> Agent
  Adapter --> Agent
  Adapter --> Cap
  Agent --> Cap
  Agent --> Core
  Cap --> Core
```

层次边界：

- **能力层不感知 agent**：`FileSystemService` 返回 `FileEntry` / `FileSlice`，不是 JSON；`BrowserService` 返回 `PageSnapshot`，不是 tool result。面向模型的 schema、参数解析、结果塑形全部位于适配层。
- **wire 协议不进入框架**：Jackson 仅出现在 `cordis.agent.llm`（OpenAI 兼容协议）与适配层的 `JsonCodec`，内核与 `cordis.agent` 不依赖 JSON 库。
- **能力层可独立使用**：无需 agent 即可直接调用 `ctx.get("filesystem").readSync(...)`、`ctx.get("browser").openSync(...)`。

### 包结构

| 包 | 角色 | 主要类 |
|---|---|---|
| `cordis.core` | 内核 | `Context` `Fiber` `Registry` `Scheduler` `Promise` `Deferred` `EventsService` `Service` `Component` |
| `cordis.sqlite` | 能力 | `SqliteService` `SqlitePlugin` |
| `cordis.http` | 能力 | `HttpService` `HttpPlugin` `SseParser` `SseCall` `HttpAccessPolicy` |
| `cordis.fs` | 能力 | `FileSystemService` `FileSystemPlugin` `PathScope` `ApprovalGate` |
| `cordis.skill` | 能力 | `SkillService` `SkillPlugin` `Skill` |
| `cordis.browser` | 能力 | `BrowserService` `BrowserPlugin` `PageSnapshot` |
| `cordis.agent` | 编排 | `AgentService` `ToolService` `ConversationService` `MemoryService` + 三个插件 |
| `cordis.agent.llm` | wire | `OpenAiLlmClient` |
| `cordis.agent.fs / http / browser / skill` | 适配 | 各工具类 + `*Tools` 组件 |
| `cordis.app` | 应用 | `AgentServer` `AgentCli` `WorkspaceInitializer` |
| `cordis.demo` | 验证 | 14 个可执行 demo，见 [验证与回归](#验证与回归) |

---

## 内核：cordis.core

### Context：extend 树 + 隔离标签

```java
Context root = Context.root();
Context child = root.extend();          // 子上下文（对应 Object.create 原型链）
Context t1 = root.isolate("database"); // 为 "database" 生成新标签，遮蔽父链
root.get("sqlite");                    // 显式解析（Java 无 Proxy 机制）
```

- 服务仓库挂载在根上下文，**以隔离标签为主键**：默认所有分支共享同一标签，兄弟插件间可相互解析。
- `isolate(name)` 之后，该分支内 `provide` / `get` 均指向新标签，与外界同名服务互不可见 —— 这是 `TenantDemo` 验证的多租户基础。
- 内部表使用 `ConcurrentHashMap`：写入仅发生在主循环，但 `get` 允许任意线程调用（实测 `HashMap` 在扩容窗口存在漏读，表现为服务间歇性不可见）。

### Fiber：6 态状态机 + effect

```mermaid
stateDiagram-v2
  [*] --> PENDING
  PENDING --> LOADING: 依赖就绪
  LOADING --> ACTIVE: apply/applyAsync 结算
  LOADING --> FAILED: 抛异常
  ACTIVE --> UNLOADING: 依赖消失 / dispose
  UNLOADING --> DISPOSED: effect 逆序回收完成
  UNLOADING --> PENDING: 依赖恢复（reload）
  FAILED --> DISPOSED
```

- **依赖驱动**：`use(component, config, inject)` 中声明的服务全部就绪后才进入 `apply`；任一服务消失则回到 `PENDING`。装配顺序无关。
- **effect**：插件运行期注册的一切资源（事件监听、服务注册、工具注册、连接）均挂在 fiber 上，卸载时**逆序**执行清理。`ctx.on(...)`、`tools.add(...)` 均返回 `Disposable`，由 fiber 持有。
- **异步初始化**：插件可实现 `applyAsync` 返回 `Promise<Disposable>`，期间 fiber 停留在 `LOADING`，主循环不被阻塞。`SqlitePlugin` / `FileSystemPlugin` / `SkillPlugin` / `MemoryPlugin` 均采用此模式。

### Scheduler：主循环 + 扩展池

| 线程 | 数量 | 职责 |
|---|---|---|
| `cordis-main-loop` | 1 | 所有状态迁移、事件派发、Promise 回调、`provide`/`get` 的写侧 |
| `cordis-worker` | `max(4, cores)` | JDBC、文件、HTTP、浏览器、重计算等一切阻塞工作 |

```java
scheduler.execute(task);        // 投递到主循环（已在主循环则内联执行）
scheduler.executeLater(task);   // 延后一拍执行（避免字段尚未赋值完成时广播）
scheduler.offload(callable);    // 投递到扩展池，返回 Promise
scheduler.defer();              // 拿 Deferred（生产方）
scheduler.awaitIdle();          // 主循环屏障，测试/编排用
scheduler.shutdown();           // 优雅关闭：扩展池 5s、主循环 1s 后再中断
```

**核心约束**：主循环上仅允许微秒级决策。任何可能阻塞的操作必须 `offload`。`*Sync` 方法（`readSync`、`openSync`、`listSync`…）内部会调用 `assertNotMainLoop`，在主循环上调用直接抛错 —— 该约束的目的是让「在事件监听器中读文件」这类错误立即暴露，而不是静默阻塞整个框架。

### Promise / Deferred

```java
Deferred<String> d = scheduler.defer();
Promise<String> p = d.promise();   // 消费方只读视图
p.then(s -> ...).catchError(e -> ...);
d.resolve("ok");                   // 任意线程可调，内部投递回主循环
```

- 单线程完成语义：回调之间不存在并发。
- `join()` 是唯一阻塞入口，仅用于编排层与测试；框架内部从不调用（主循环上调用 `join` 会自死锁，已有 fail-fast 保护）。
- 组合子：`then` / `thenAsync` / `catchError` / `handle` / `all` / `allSettled`。

### EventsService

```java
ctx.on("agent/tool", args -> { ... });          // 随 fiber 自动解绑
ctx.events.emit("agent/reply", reply);           // 任意线程可调
ctx.events.waterfall("system/prompt", base, ...);// 监听器可改写值
ctx.events.parallel("cleanup", ...);             // 收集所有异常
```

五种模式：`emit`（同步广播）、`serial`（依次等待）、`bail`（首个非 undefined 即停）、`waterfall`（管道改写）、`parallel`（并发收集异常）。监听器返回 `Promise` 时自动进入异步链路。

### Service / Component

```java
// 服务：构造即注册（对应 TS 构造函数里的 reflect.provide）
public class MyService extends Service {
    public MyService(Context ctx) { super(ctx, "my.service"); }
}

// 组件：定义（一份定义 → 档案 Runtime → 任意多 fiber）
public class MyPlugin implements Component {
    public String getName() { return "my-plugin"; }
    public Disposable apply(Context ctx, Map<String, Object> config) {
        ctx.on("some/event", args -> { ... });
        return () -> { /* 清理，卸载时执行 */ };
    }
}
```

`Component.named(name, lambda)` 可为匿名组件指定名称；`ToolComponent` 演示「每个工具对应一个组件」。

---

## 能力层

### SQLite：两段式异步初始化范例

```java
root.registry.use(SqlitePlugin.INSTANCE, Maps.of("url", "jdbc:sqlite:/tmp/app.db"), null);
SqliteService db = root.get("sqlite");

db.execute("INSERT INTO t(a) VALUES(?)", 42);       // 阻塞方法，须在 worker 上调用
List<Map<String, Object>> rows = db.query("SELECT * FROM t");
db.transaction(() -> { ... });                      // 失败自动回滚 + 复原 auto-commit
```

插件实现 `applyAsync`：`DriverManager.getConnection` offload 到扩展池 → 回主循环 `new SqliteService(...)`（`provide` 必须在主循环执行）→ ping 自检后再次 offload。慢初始化期间主循环无阻塞。

### HTTP / SSE

```java
HttpService http = root.get("http");
http.get("https://example.com").then(res -> res.body);
http.postJson(url, json).then(res -> res.status);

// SSE：逐事件回调，零阻塞
SseCall call = http.stream(request, event -> { ... });
call.dispose();     // 或等 call.completion()
```

- 采用 OkHttp `enqueue` 原生异步，不占用扩展池（与 JDBC 的 offload 路径形成对照）。
- `SseParser` 是逐行增量状态机：空行派发、`:` 注释忽略、多行 `data` 拼接、无 `data` 不派发。
- `HttpAccessPolicy` 用于限定模型可访问的地址范围，默认 deny-all；`HttpCaller` 对**每一跳重定向**重新校验，防止白名单地址通过 302 跳转绕过策略访问内网。

### 文件系统

```java
FileSystemService fs = root.get("filesystem");
fs.readSync("src/Main.java", 1, 200);
fs.writeSync("notes.md", "# hi");        // 过 ApprovalGate 才落盘
fs.grepSync("TODO", "*.java");
```

安全模型四层：

1. **沙箱**：`PathScope` 先做词法校验（`normalize` 后必须仍位于 root 内，绝对路径与 `../` 直接拒绝），再对已存在部分做 `toRealPath()` 比对（防止符号链接逃逸）。
2. **审批**：写入/修改前经过 `ApprovalGate`，默认拒绝一切。
3. **上限**：`maxReadBytes`（2MB）/ `maxWriteBytes`（1MB），先校验再执行。
4. **原子写**：先写同目录临时文件再 `ATOMIC_MOVE`，进程中途终止不会留下不完整的目标文件。

二进制文件（前 8KB 含 NUL）直接拒绝，避免将字节流按 UTF-8 解码后回写。

### 技能（skill）

```
<skills>/
  markdown-writer/
    skill.json     ← {"description": "..."}，可缺省
    SKILL.md       ← 正文（按需加载）
    references/    ← 二级资源
```

- **清单**（名称 + 一句话描述）注入 system prompt，常驻但体量很小；**正文**按窗口续读（`bodySync(name, offset)`），避免一次性占满上下文。
- skill 目录的增删改不会自动生效，需重装 `SkillPlugin`（fiber reload 触发重扫描）；此处复用框架的热插拔机制，不额外实现文件监听。

### 无头浏览器

```java
BrowserService browser = root.get("browser");
PageSnapshot snap = browser.openSync("https://www.sogou.com/", 800);
// snap.snapshot 为带 [ref=e12] 标记的无障碍树
browser.typeSync(snap.pageId, "e4", "北京天气", true);
PageSnapshot after = browser.snapshotSync(snap.pageId);   // 每次操作后重新取快照，旧 ref 可能失效
browser.screenshotSync(snap.pageId);
browser.closeSync(snap.pageId);
```

设计要点：

| 决策 | 依据 |
|---|---|
| `ariaSnapshot(mode=AI)` + `getByRef("e5")` | 快照体积为数百字符（`innerHTML` 通常为数万字符），模型无需编写 CSS 选择器；1.64+ 提供官方 agent 能力 |
| 一个页面 = 一个 `BrowserContext` | cookie / storage 天然隔离 |
| 每页一把 `ReentrantLock` | Playwright `Page` 非线程安全 |
| 仅校验 main frame 导航与重定向后的 final URL | 浏览器自身会发起 XHR 等子请求，无法逐条白名单校验 |
| 拒绝 `file://` / `data:` | `file://` 可读取本机任意文件 |
| 懒启动 + 插件异步预热 | 首次 `create` 8.9s + `launch` 3.7~4.3s，预热后首次工具调用无需等待 |
| `channel=chrome` + 自动注入 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` | 使用系统 Chrome，可省去整套 chromium + headless_shell 的 583MB 下载（实测）；未安装 Chrome 时显式报错，而非隐式下载 |

---

## Agent 层

### AgentService：loop 本体

```
user input
  └─ for step in 1..maxSteps:
       工具快照 → llm.chat(历史, 快照)
         ├─ 无 toolCalls → emit(reply) → AgentResult(ANSWERED)
         └─ 并发执行本轮全部工具 → 结果回填历史 → 下一轮
```

- 整个 loop 运行在**扩展池的单个 worker** 上：模型调用与工具执行均在该 worker 同步进行，主循环全程不参与。
- 工具并发使用 `AgentService` **自带的**小线程池，而非 `scheduler.offload`：loop 已占用一个 worker，若再从该 worker offload 并等待结果，扩展池在等待子任务时可能自锁。
- 事件：`agent/step`、`agent/reply`、`agent/tool`，监听器可获取本次 run 的 `AgentListener`。
- 取消粒度为轮：调用 `AgentCall.dispose()` 后不再开始下一轮，已产生的消息仍会落库。

### ToolService 与工具热插拔

```java
ToolService tools = root.get("agent.tools");
Disposable reg = tools.add(new MyTool());   // 注册
reg.dispose();                              // 下线；下一轮快照中不再出现
```

`add` 返回的 `Disposable` 通常由 `ToolComponent` 作为 effect 交给 fiber 持有，从而使每个工具对应一个 fiber：热卸载与重装完全复用生命周期模型。

### MemoryService

```sql
sessions(id TEXT PK, created_at INTEGER, updated_at INTEGER)
messages(id INTEGER PK AUTOINCREMENT, session_id TEXT, seq INTEGER,
         role TEXT, content TEXT, tool_calls TEXT, tool_call_id TEXT, created_at INTEGER,
         UNIQUE(session_id, seq))
```

- `seq` 由单条 `INSERT ... SELECT COALESCE(MAX(seq),0)+1` 在数据库侧原子分配，并发追加不会重号。
- 一轮对话的多条消息用事务包起来；`loadSync` 发现尾部残缺配对（有 `tool_calls` 没有 `tool` 结果）会截断并 emit `memory/repaired`。

### ConversationService：一次调用串起三段

```java
ConversationService conv = root.get("conversation");
conv.send("user-42", "我叫小明").completion().join();
```

`load → loop → save` 在同一个 worker 上顺序执行（节省一个 worker，并避免同一会话的读写分散到两个 worker 上交错执行）。落库失败不影响返回结果，仅 emit `conversation/persist-error`。

### OpenAiLlmClient：流式聚合

SSE 的 `delta.tool_calls` 分片先经 `ToolCallAggregator` 归并为完整 `ToolCall`，再返回给 loop，loop 无需感知是否流式。同时保留「先输出文本再调用工具」的文本内容至历史。

---

## 适配层与工具清单

所有工具均为能力层的薄封装：解析参数 → 调用 `*Sync` 方法 → 输出 JSON。`Tool.run` 本身运行在 worker / toolPool 上，无需再次 offload。

| 工具 | 所属组件 | 需要能力 | 作用 |
|---|---|---|---|
| `list_dir` `read_file` `write_file` `edit_file` `grep` | `agent.fs` | `filesystem` | 沙箱内读写、按行读、精确替换、正则搜索 |
| `http_get` `http_post` | `agent.http` | `http` | 过白名单的 HTTP 调用，重定向逐跳校验 |
| `browser_open` `browser_snapshot` `browser_click` `browser_type` `browser_press` `browser_wait` `browser_screenshot` `browser_close` | `agent.browser` | `browser` | 打开页面、取快照、按 `ref` 点击/输入/按键、等待、截图、关页 |
| `use_skill` | `agent.skill` | `skills` | 按名字加载技能正文（清单由 `SystemProvider` 拼进 system） |

装配模式统一：

```java
root.registry.use(FileSystemTools.INSTANCE, Maps.of(),
        Maps.of("filesystem", true, "agent.tools", true));
root.registry.use(HttpTools.INSTANCE, Maps.of("policy", policy),
        Maps.of("http", true, "agent.tools", true));
root.registry.use(BrowserTools.INSTANCE, Maps.of(),
        Maps.of("browser", true, "agent.tools", true));
root.registry.use(SkillTools.INSTANCE, Maps.of(),
        Maps.of("skills", true, "agent.tools", true));
```

`inject` 决定两点：依赖未就绪时 fiber 停留在 `PENDING`；`apply` 中 `ctx.get(...)` 必然可用（提供者已处于 ACTIVE）。缺失任一项都会抛出可定位的错误，而非 NPE。

---

## 应用：AgentServer / AgentCli

### 启动

```bash
export CORDIS_JAVA_APP_ID=<你的 OpenAI API Key>
export CORDIS_JAVA_BASE_URL=https://api.openai.com/v1
java -cp "$CP" cordis.app.AgentServer [选项]
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `--port` | 8787 | `0` = 系统分配 |
| `--bind` | 127.0.0.1 | 绑定地址 |
| `--root` | `./agent-workspace` | 工作区（文件系统沙箱边界） |
| `--skills` | `<root>/skills` | 技能目录 |
| `--allow-write` | 关闭 | 未指定时写操作全部被拒 |
| `--allow-host <h>` | 无 | HTTP 白名单，可重复指定；`*` 表示全部放行（仅建议本地调试）。未配置时不装配 HTTP 工具 |
| `--enable-browser` | 关闭 | 装配 8 个 `browser_*` 工具；导航策略复用 `--allow-host` |
| `--browser-channel` | `chrome` | 使用系统 Chrome；置空则使用 Playwright 自带二进制 |
| `--browser-headful` | 无头模式 | 以有界面模式启动，便于观察 |
| `--max-steps` | 32 | 单次对话的模型轮数上限；打满后**强制收尾**——不带工具清单再问一次出总结，`stopReason` 仍为 `MAX_STEPS` |
| `--max-sessions` `--model` `--base-url` | — | 见 `--help` |

### 会话记忆（SQLite 持久化）

会话历史落在 **`<root>/memory.db`**（SQLite 文件），重启进程不丢。**会话由客户端传的
`session` 字符串识别**：`/chat` 传同一个 session id 就接上同一段历史，与是否重启无关；
不传 session 则新开随机会话。`--max-sessions` 只限制内存缓存条数，满了整体清空后
按需重读，不影响持久化数据。`/reset` 同时删除库内历史与缓存。配套的
`AgentCli --resume` 会读回上次保存的 session id（存在当前目录 `.agent-cli-session`），
服务端重启后也能续上上回的对话。

**发给模型的历史只含问答主干**：`messages` 表的 `qa` 列标记每行身份——`1` =
用户问题与模型终答，`0` = 工具调用过程（带 `tool_calls` 的 assistant、tool 结果）。
所有消息都**全文**落库（`content` 列存原始信息，含网页/文件原文），但每轮重发给
模型的只有 `qa=1` 的问答主干——工具返回的大段原文不再反复计 token，长会话负担不随
轮次滚雪球；过程原文仍留在库里，需要回溯时可查（`MemoryService.load` 读全量档案视图）。

### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 模型、记忆文件（`memory` 字段）、可见工具、技能数、写入策略、会话数、`http`/`httpScope`、`browser`/`browserScope`/`browserPages` |
| POST | `/chat` | `{"session":"可选","message":"...","stream":false}` → `{"session","answer","stopReason","steps","toolCalls","elapsedMs","events"}` |
| POST | `/chat` | 加 `"stream":true` 或 `Accept: text/event-stream` → 逐帧 `data: {"type":...}` |
| POST | `/reset` | `{"session":"..."}` 删除该会话的库内历史与内存缓存 |
| POST | `/history` | `{"session":"..."}` → `{"session","count","messages":[{"role","content"}]}` 回放问答主干（qa=1，工具过程不返回） |

SSE 帧类型：`step`（进入第 n 轮，带当前工具快照）、`tool_start`、`tool`（工具结果）、`final`、`error`。

### 并发模型

锁仅保护单个会话的 `history`：同一会话串行，不同会话可真正并发。过程事件通过每次 run 独立的 `AgentListener` 传递，天然按次隔离，无需全局串行。

### AgentCli

```bash
java -cp "$CP" cordis.app.AgentCli [url] [--no-stream] [--session id] [--resume]
```

默认读取 SSE 并逐帧输出；`--no-stream` 回退为一次性 JSON；`--session` 显式指定会话；
`--resume` 续用上次保存的会话 id；内置 `/help` `/health` `/history` `/reset` `/exit`。

---

## 线程模型与编码约定

编写插件时需遵循四条约定：

1. **主循环只做决策**。可能阻塞的操作一律调用 `scheduler.offload(...)`，或采用 `HttpService` 的原生异步回调。
2. **`Service` 构造必须在主循环**（`ctx.provide` 会断言）。因此 `applyAsync` 的模式始终为「扩展池准备 → `then` 回主循环构造」。
3. **`*Sync` 只能在 worker 上调用**。在主循环调用会 fail-fast；该规则使「在监听器中读取文件」这类操作立即成为显式错误。
4. **清理逻辑交给 effect**。`apply` 返回的 `Disposable` 与 `ctx.on(...)` 的返回值均由 fiber 逆序回收，无需在插件内另行管理生命周期。

---

## 日志

划分原则：**程序输出走命令行，运行期事件走 slf4j**。

| 输出者 | 通道 | 理由 |
|---|---|---|
| `cordis.demo.*` 的断言与演示 | `System.out` / `System.err` | 输出格式即为契约：`java -cp ... Demo && echo ok`，`[PASS]` / `[FAIL]` 由脚本 grep 校验 |
| `AgentCli` | `System.out` | 其本身即交互式 UI |
| `AgentServer` 的 `--help`、启动 banner | `System.out` | 一次性、面向人工阅读的控制台输出 |
| `AgentServer` 参数错误 / 启动失败 | `System.err` + 非 0 退出码 | 使参数错误在 shell 中体现为执行失败 |
| 框架运行期（`core` / 能力层 / `agent` / `app` 请求处理） | slf4j | 输出目标、级别与格式应由装配方决定，库不应硬编码 stdout |

框架代码仅依赖 `slf4j-api`（编译期），`slf4j-simple` 仅为 `runtime` 便捷 binding；替换 logback / log4j2 / jul 只需更换该依赖，业务代码无需改动。

`slf4j-simple` 默认级别为 INFO 且不输出时间戳，生产环境建议配置：

```bash
java -Dorg.slf4j.simpleLogger.showDateTime=true \
     -Dorg.slf4j.simpleLogger.dateTimeFormat='yyyy-MM-dd HH:mm:ss.SSS' \
     -cp "$CP" cordis.app.AgentServer
```

级别约定：

| 级别 | 用在 |
|---|---|
| ERROR | 框架内部不可恢复：主循环任务抛出、fiber 挂载/卸载失败、`handle` 之外必须留痕的异常 |
| WARN | 降级但继续：监听器抛错、事务回滚/复原失败、HTTP 500、浏览器预热失败 |
| INFO | 一次性生命周期事实：workspace 就绪、技能数、driver 启动与耗时、浏览器预热完成 |
| DEBUG | 高频或纯诊断：连接建立、临时文件清理失败、driver 关闭 |

---

## 安全模型

所有默认值均为关闭，需显式启用：

| 关口 | 默认 | 启用方式 |
|---|---|---|
| 文件写入 | 拒绝一切 | `--allow-write`，或装配时传 `(ApprovalGate) request -> true` |
| 文件读取范围 | 工作区沙箱 | `FileSystemPlugin` 的 `root` |
| HTTP 出网 | 不装配工具 | `--allow-host` / `HttpTools` 的 `policy` |
| 浏览器导航 | 不装配工具；装配后仍为 deny-all | `--enable-browser` + `--allow-host` |
| 浏览本机文件 | 始终拒绝 | `file://` / `data:` 直接拒绝 |

设计取舍：**允许模型自行构造 URL 等同于授予内网访问能力**，因此网络能力默认不存在，而非存在但受限。三道关口（fs / http / browser）相互独立，可按需单独启用。

---

## 验证与回归

全部 demo 均由 `main` 方法与断言构成，不依赖测试框架：

```bash
CP="target/classes:$(cat target/cp.txt)"
for d in LifecycleDemo TenantDemo HttpDemo SqliteDemo SseDemo AgentDemo MemoryDemo \
         AgentHardeningDemo FsAgentDemo SkillAgentDemo AgentServerDemo HttpToolDemo BrowserToolDemo; do
  java -cp "$CP" cordis.demo.$d > /dev/null 2>&1 && echo "$d ok" || echo "$d FAILED"
done
```

| Demo | 验证内容 |
|---|---|
| `LifecycleDemo` | 异步调度、慢初始化不阻塞、依赖驱动挂载、effect 逆序回收 |
| `TenantDemo` | `isolate` 分支隔离、同名服务互不可见、分支卸载不污染其他租户 |
| `SqliteDemo` | 两段式初始化、依赖顺序无关、参数化查询、事务 |
| `HttpDemo` | OkHttp 并发（10×200ms 总耗时接近单次）、超时、错误码 |
| `SseDemo` | SSE 逐事件、主循环零阻塞、主动取消、错误路径 |
| `AgentDemo` | 脚本 LLM 打通 loop、工具热插拔、取消、流式分片聚合 |
| `MemoryDemo` | 对话落库、业务插件重装后历史仍在、`tool_calls` 往返无损 |
| `AgentHardeningDemo` | 工具异常回填合法 JSON、轮次事务、残缺配对自修复 |
| `FsAgentDemo` | 沙箱/审批/大小上限 + 真实模型「读取 → 修改 → 再读取」（section 3 在缺少 `CORDIS_JAVA_APP_ID` 时自动跳过） |
| `SkillAgentDemo` | 扫描、清单、窗口续读 + 「清单 → 选择 → 加载正文 → 读资源」（section 3 同样自动跳过） |
| `AgentServerDemo` | `/health` 三态、流式与双会话并发的事件隔离、浏览器工具装配四态（5+3+4 条） |
| `HttpToolDemo` | 13 条断言，覆盖白名单、`*` 全开、重定向逐跳、截断、二进制、POST 回显、热插拔 |
| `BrowserToolDemo` | 13 条断言，覆盖本地表单页 open→ref→type→click→读取 JS 执行结果、异步等待、截图、`file://` 与白名单拒绝、多页隔离 |
| `OpenAiAgentDemo` | 真实模型（需 `CORDIS_JAVA_APP_ID`）：多步 loop、流式与非流式结果一致性校验 |

实测数据：Playwright 首次 `create` 8875ms、`launch` 3674~4302ms、`navigate` 913~4095ms；`PLAYWRIGHT_BROWSERS_PATH` 为空时 `channel=chrome` 仍可正常使用（Chrome 158）。

---

## 已知限制

- **未移植 `symbols.intercept`**：TS 用它在本分支覆盖服务配置；本范例无消费方，故不提供表面生效、实际无消费方的 API。
- **同一 session 的并发 `send` 未串行化**：`seq` 不会重复（数据库侧原子分配），但对话语义可能交错。需要严格串行时由调用方按 `sessionId` 加锁。
- **skill 变更需重装插件**：未实现文件监听，重扫依靠 fiber reload。
- **浏览器反爬为客观限制**：百度 `/s?wd=` 会返回安全验证页或导航超时，搜狗可正常使用。结构化数据（如天气）应使用官方 API，浏览器仅用于必须执行 JS 的页面。
- **Playwright 体积**：`driver-bundle` 218MB，不应打包进 fat jar；生产镜像建议预装浏览器并设置 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`。
- **未集成 JUnit**：验证依赖 demo 内断言，无 CI 集成；如需可在 pom 中引入 surefire 与 JUnit 4。

---

## 目录结构与阅读顺序

```
src/main/java/cordis/
  core/      ← 优先阅读：Context → Fiber → Scheduler → Promise → EventsService
  sqlite/    ← 两段式 `applyAsync` 的最简范例
  http/      ← 原生异步桥接 + SSE + 白名单
  fs/        ← 沙箱与审批
  skill/     ← 清单/正文分离
  browser/   ← Playwright 封装
  agent/     ← loop、工具、记忆、会话
  agent/*/   ← 工具适配层（薄封装）
  app/       ← AgentServer / AgentCli
  demo/      ← 各能力均配有可执行断言
```

推荐顺序：`LifecycleDemo` → `core` 五个类 → `SqliteDemo` + `SqlitePlugin` → `HttpDemo` → `AgentDemo` → `AgentServer`。

---

## 依赖与版本选择

| 依赖 | 版本 | 选型依据 |
|---|---|---|
| JDK | 8 | 目标基线；`maven.compiler.source/target = 1.8` |
| `sqlite-jdbc` | 3.21.0.1 | 纯 Java 实现、无本地库依赖，支持内存库与文件库 |
| `okhttp` | 3.14.9 | 4.x 起改用 Kotlin 实现并引入 `kotlin-stdlib`；本项目仅需 `enqueue` 异步回调 |
| `jackson-databind` | 2.17.2 | 仅用于 wire 层（OpenAI 兼容协议）；2.17 为 Java 8 基线，3.x 要求 JDK 17 |
| `playwright` | 1.65.1 | 官方主 pom 仍为 source/target 8；1.64+ 提供 `ariaSnapshot(AI)` 与 `getByRef` |
| `slf4j-api` | 2.0.17 | 日志门面，2.x 基线 Java 8；框架仅依赖 API，不绑定实现 |
| `slf4j-simple` | 2.0.17 | `runtime` scope 的便捷 binding（输出至 stderr），供 demo 与 AgentServer 直接运行；作为库引入时应替换为自有实现 |

内核除了 `slf4j-api` 不依赖任何第三方库：`cordis.core` 其余只用 JDK 的 `java.util.concurrent` 与函数式接口。
