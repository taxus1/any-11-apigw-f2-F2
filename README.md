# apigw · 微服务网关

Spring Cloud Gateway（WebFlux 响应式）+ Redis 动态路由配置。JDK 17 / Spring Boot 3.2.5 / Spring Cloud 2023.0.1。

同一个应用里跑四件事：

1. **转发链路**：请求进来 → 按配置的匹配条件找到路由 → 按配置的转发动作处理请求头 → 打到上游 → 响应回来处理响应头 → 交还调用方。配置在 Redis，改完经事件即时生效，不用重启（另有定时兜底刷新保证多实例最终一致）。
2. **路由管理接口**：`/api/gateway/routes`，维护路由及其匹配条件、转发动作（含每条路由要不要登录的 `requireLogin` 标记）。
3. **第三方接入管理 + 鉴权**：`/api/gateway/apps`，维护第三方应用凭据与来路名单；开启后转发流量必须凭「应用编号 + 密钥」通过鉴权才放行。
4. **用户登录鉴权 + 身份透传**：路由级开关 `requireLogin`，内部路由凭网关签发的 Bearer JWT 放行；验过把用户/租户经 `X-Auth-User` / `X-Auth-Tenant` 透传上游、每笔请求盖上游可验的 `X-Gateway-Stamp`，原始令牌不上递、伪造身份头先剥再写。

## 起环境

```bash
docker compose up -d                # 起 Redis（路由配置存在这里）
mvn spring-boot:run                 # 网关，8080
bash tools/start-echo-upstream.sh   # 本地回显上游，8091（另开一个终端）
```

## 管理接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/gateway/routes` | 新建路由（连同条件与动作一起落库） |
| PUT | `/api/gateway/routes/{routeNo}` | 修改路由（整树替换，必须带 `version`） |
| GET | `/api/gateway/routes/{routeNo}` | 路由详情（含全部子项，按顺序号排好） |
| GET | `/api/gateway/routes?pageNum=&pageSize=&keyword=` | 分页列表（每条带条件/动作计数） |
| DELETE | `/api/gateway/routes/{routeNo}?expectVersion=` | 删除路由（整树清掉） |
| GET | `/api/gateway/access-logs?startTime=&endTime=&routeNo=&statusCode=&pageNum=&pageSize=` | 按条件翻访问流水（时间必填，见「翻流水」） |

所有接口返回统一结构 `{ code, msg, data }`：

- `code=0` 成功；
- 通用业务失败 `code=1`；
- 路由不存在 `code=404`（删除不存在的路由不算成功）；
- 并发冲突 `code=409`（你手上的版本旧了）。

### 请求体形状

```json
{
  "routeNo": "order-route",
  "name": "订单服务路由",
  "upstream": "http://order-svc:8080",
  "enabled": 1,
  "requireLogin": 0,
  "remark": "给前端下单用",
  "version": 0,
  "conditions": [
    { "type": "PATH_PREFIX", "value": "/order/", "sortNo": 1 },
    { "type": "METHOD", "value": "GET", "sortNo": 2 },
    { "type": "HEADER", "name": "X-Caller", "value": "web", "sortNo": 3 },
    { "type": "QUERY", "name": "from", "value": "cart", "sortNo": 4 }
  ],
  "actions": [
    { "type": "REQ_ADD_HEADER", "name": "X-Gw", "value": "1", "sortNo": 1 },
    { "type": "REQ_REMOVE_HEADER", "name": "X-Internal", "sortNo": 2 },
    { "type": "RESP_ADD_HEADER", "name": "X-Trace", "value": "t-1", "sortNo": 3 },
    { "type": "RESP_REMOVE_HEADER", "name": "X-Debug", "sortNo": 4 }
  ]
}
```

- 路由编号：业务唯一，建后**不可改**（PUT 的 body 里编号与路径不一致会被拦）；停用的路由也占号，只有删除才释放编号。
- `requireLogin`：这条路由要不要登录，只认 `0`（开放，默认）/ `1`（必须登录）。开放路由谁都能打、没带令牌也放行；必须登录的路由必须带一张网关签发的有效令牌（详见「用户登录鉴权与身份透传」）。**开启用户登录鉴权功能后该标记才生效**，功能关闭时不做任何令牌校验。
- 匹配条件只认 `PATH_PREFIX` / `METHOD` / `HEADER` / `QUERY`；路径、方法两类不用填 `name`。
- 转发动作只认 `REQ_ADD_HEADER` / `REQ_REMOVE_HEADER` / `RESP_ADD_HEADER` / `RESP_REMOVE_HEADER`；删头不用填 `value`。
- 顺序号每组各自从 1 开始，必须**连续、不重**。撞号会报「匹配条件第 a 条与第 b 条的顺序号撞了，都是 n」；跳号会报缺了第几。
- 上游地址必须是合法的 `http://` / `https://` URL（协议、主机、端口都像样），空串和乱码不收。
- 修改时把条件/动作整批重排提交即可，服务端按新一批顺序号整树替换。

### 分页返回

```json
{
  "code": 0,
  "data": {
    "content": [ { "routeNo": "...", "conditionCount": 2, "actionCount": 4, "...": "..." } ],
    "total": 37,
    "pageNum": 2,
    "pageSize": 20,
    "totalPages": 2
  }
}
```

- `pageNum` 从 1 开始；`pageSize` 上限 200（传 99999 也只按 200 算），防止一次拖全量。
- `keyword` 在编号和名称上做忽略大小写的模糊匹配。
- 列表每行直接带 `conditionCount` / `actionCount`，前端不用逐条再查。

## 转发链路怎么走

转发由一个高优先级 WebFilter（`com.apigw.proxy.GatewayProxyWebFilter`）总编排，管理接口 `/api/**` 直接放行，其余请求按下面的路走：

```
请求进来
 → 生成 traceId，写访问日志第 1 段（phase=IN）
 → （开启用户登录鉴权时）按 requireLogin 分流：内部路由验令牌，开放路由尽力识别身份；
    剥光伪造身份头、写 X-Auth-User/X-Auth-Tenant、盖 X-Gateway-Stamp、剥原始 Authorization
 → 在内存路由快照上匹配唯一路由（匹配不到 → 404 NO_ROUTE）
 → 清洗逐跳/报文绑定头 + X-Forwarded-* + 按顺序号执行请求类动作
 → 发到上游（请求体流式透传，不缓冲）
 → 上游响应回来：状态码原样，清洗逐跳/content-length，按顺序号执行响应类动作
 → 响应体流式写回调用方，写访问日志第 2 段（phase=OUT，含状态码/耗时/命中路由/上游）
```

### 匹配细节

- 同一条路由上的条件是 **AND**，任何一条不满足就不命中。
- **路径前缀边界**（最容易踩的点）：
  - 规则 `/order/`（带尾斜杠）= 只认子树：命中 `/order/abc`、`/order/`，但**不**命中 `/order` 本身；
  - 规则 `/order`（不带尾斜杠）= 精确路径 + 子树：命中 `/order`、`/order/abc`，但**不**命中 `/other`、`/ordering`、`/order-x`、`/orders/1`（下一个字符必须是 `/`）。
  - 路径大小写敏感（常规 URL 语义）。
- 方法名大小写不敏感（`GET` 与 `get` 等价）；HEADER 头名不敏感、头值大小写敏感且精确相等；QUERY 名值都大小写敏感、值精确相等。
- **多条路由同时命中时的定序**（确定、稳定，同样的请求永远走同一条）：
  1. 路径前缀更长的优先（更具体的路径赢；没有路径条件的按 0 长度排最后）；
  2. 仍并列时条件总数更多的赢（约束更具体）；
  3. 还并列按路由编号字典序（routeNo 只含字母数字 `. _ -`）。
- 停用的、以及一条条件都没有的路由不参与匹配。

### 动作语义

- 补头是**覆盖**语义：调用方自带同名头会被配置值顶掉（HTTP 头名大小写不敏感）；删头就是删掉，上游/调用方都收不到。
- 同方向动作严格按 `sortNo` 顺序执行（先删后补与先补后删结果相反）。
- 方向严格隔离：`REQ_*` 只作用于发往上游的请求头，`RESP_*` 只作用于回给调用方的响应头。
- 真正落到报文上（不是记日志）：上游收到的请求头、调用方收到的响应头都按动作改写过。
- 上游返回的 `content-length`、`transfer-encoding` 与「上游↔网关」这段具体报文绑定，**不原样照抄**：网关在提交前剔除，由 Netty 按网关实际写出的字节重算/走分块，避免长度与内容对不上。

### 错误答复（四类，状态码 + `X-Gateway-Error` 头 + JSON 体 `{error,message,traceId}`）

| 场景 | HTTP | X-Gateway-Error | 含义 |
| --- | --- | --- | --- |
| 一条路由都没匹配上 | 404 | `NO_ROUTE` | 网关没找到路，前端据此与「后端业务报错」区分 |
| 上游连不上（拒接/不可达/TLS 失败） | 502 | `UPSTREAM_UNAVAILABLE` | 上游没在或地址错 |
| 上游半天不吭声（连接/读取超时） | 504 | `UPSTREAM_TIMEOUT` | 上游在但太慢/卡死，调用方不用干等 |
| 路由配置此刻读不出来（Redis 故障且无旧快照） | 503 | `CONFIG_UNAVAILABLE` | 网关侧配置故障 |
| 必须登录路由：令牌缺失/签名错/过期/声明不全 | 401 | `TOKEN_UNAUTHENTICATED` | 用户登录鉴权（详见专章），文案不区分原因 |
| 登录鉴权时路由配置读不出来 | 503 | `TOKEN_CONFIG_UNAVAILABLE` | 无法判断要不要登录，fail-closed |

- 上游自己的 4xx/5xx 是业务结果，状态码与响应体**原样透传**，网关不改写。
- 错误体只有网关的固定文案 + traceId，绝不外抛内部堆栈或上游原始错误页。
- 超时参数可调：`apigw.proxy.connect-timeout`（默认 3s）、`apigw.proxy.response-timeout`（默认 10s）。

### 热刷新（不重启生效）

- 管理接口增/删/改成功后发布进程内 `RoutesChangedEvent`，本实例的路由快照立即重载——新配路由**马上能走通**。
- 另有 10s 定时兜底刷新（`apigw.proxy.route-refresh-interval`），多实例部署时别的实例改了配置，靠它在一个周期内收敛。
- Redis 一时抖动：已有快照时沿用上一份继续转发，只在从没加载成功过时回 503。

### 访问审计（查账）

审计有两份产物，互为补充：

**1）文件式两段日志**：专用 logger `access-log`，同一次请求记两段，靠同一个 `traceId` 拼回，不会串到别人：

  - `phase=IN  traceId=... method=... path=... route=- upstream=-`
  - `phase=OUT traceId=... method=... path=... route=... upstream=... status=... outcome=... elapsed=...ms`

命中路由、上游地址、耗时、最终状态码、结果（FORWARDED/NO_ROUTE/UPSTREAM_*/CONFIG_UNAVAILABLE）都在 OUT 段；没匹配上的请求也记。
写日志走独立守护线程 + 有界队列，反应式链路里只做一次微秒级入队；队列满宁可丢日志并计数告警，也不反压转发。

**2）访问流水表（一笔请求一行，`gw_access_log`）**：DDL 见 `src/main/resources/db/gw_access_log.sql`，列含义：

| 列 | 口径 |
| --- | --- |
| request_id | 请求编号：调用方带了合法 `X-Trace-Id` 就沿用，没带/非法由网关生成 32 位十六进制串；与响应头 `X-Gateway-Trace-Id` 同一个号，跨服务可串联 |
| route_no | 命中路由编号；没匹配上为 NULL |
| app_no | 调进来的应用（`X-App-No`，白名单校验），认不出来为 NULL |
| client_ip | 来源地址，口径同鉴权/限流，见下「来源地址口径」 |
| method / path | 请求方法 / 应用内路径 |
| status_code | **最终回给调用方的状态码**：上游码原样透传；上游失败/超时/没连上时填网关合成的 502/504（配置读不出 503、无路 404），失败请求一样留痕；状态行写出前连接就断、一个码都没产出填 `0`（不允许 NULL） |
| elapsed_ms | 请求总耗时（毫秒） |
| occurred_at | 发生时间（请求到达时刻，毫秒精度） |

**来源地址口径（全网关唯一实现 `ClientIpResolver`，鉴权/限流/流水共用，不许另写）**：
依次取 `X-Forwarded-For` 最左一个合法地址 → `X-Real-IP` → 传输层 `remoteAddress`；
头里的值必须逐段是合法 IPv4/IPv6 字面量才采纳（不做 DNS、不收主机名），伪造值整级跳过往后退。

**并发不串请求**：请求一进来就为这笔请求建一个自己的流水持有者（请求栈上的局部对象，进来段定死编号/应用/来源/方法/路径/发生时刻），
响应收口时在**同一份对象上**补齐路由/状态码/耗时，凑成完整一行异步入库——不用共享 Map 按 traceId 凑，
A 的路径绝不可能配到 B 的状态码。

**异步攒批落库（`AsyncBatchingAccessLogSink`，不拖慢转发）**：

- 请求线程只做一次**非阻塞**有界队列入队；攒批、批量写全在独立守护线程 `access-log-db-writer`；
- 攒批三边界：凑满 `apigw.accesslog.batch-size`（默认 500）立刻落；没满最多等 `flush-interval`（默认 2s）必落；
  正常退出（SmartLifecycle，Web 容器先停）把队列里剩余记录按批 drain 完，等待封顶 `shutdown-await`（默认 10s），超时不再等、进程退得掉；
- 队列满（默认 2 万）直接丢这一条并计数告警（每 1000 条打一次 warn），**绝不反压转发**；
- 每批 `addBatch/executeBatch` 显式包在**一个事务**里，整批提交或整批回滚，不存在「半条记录」；
  写库失败/库抖动只 warn + 计数，异常不出写线程、也不碰转发主职责；写线程遇意外异常不死亡。

开关与参数（`apigw.accesslog.*`）：默认**关闭**（无库也能本地起网关），生产置 `ACCESSLOG_ENABLED=true`
并配 `ACCESSLOG_DB_URL/USER/PASSWORD` 即生效；关闭时注入空实现，转发链路零差别。

### 翻流水

`GET /api/gateway/access-logs`，同样返回统一 `Result`，分页结构与路由列表一致：

```
/api/gateway/access-logs?startTime=2026-09-26T10:00:00Z&endTime=2026-09-26T11:00:00Z
                        &routeNo=order-route&statusCode=502&pageNum=1&pageSize=20
```

- `startTime`（含）/`endTime`（不含）必填，ISO-8601：带 `Z`/偏移按带的解释，不带偏移按 UTC；
- `routeNo`、`statusCode` 可选，条件彼此 AND，可任意组合；
- `pageNum` 从 1 开始，`pageSize` 默认 20、**上限 200**；
- 返回 `content / total / pageNum / pageSize / totalPages`，`total` 与当前页在**同一只读事务**里取，严格对得上；
- 护栏：时间跨度上限 7 天、翻页深度上限 10 万行（要更早数据请缩小时间窗，深翻页不真跑大 OFFSET），
  JDBC 阻塞调用统一切到 `boundedElastic`，不占 Netty 事件循环。

**索引（随 DDL 建好）及为什么**：

- `PRIMARY KEY(id)`：自增主键，写入顺序追加，InnoDB 聚簇；
- `idx_occurred_at(occurred_at, id)`：最常用的按时间段翻页走范围索引；`id` 收尾让 `ORDER BY occurred_at, id`
  与索引顺序一致，同一毫秒内分页不重不漏、也不用 filesort；
- `idx_route_time(route_no, occurred_at)`：等值列在前（路由编号等值）、范围列在后（时间范围），组合筛选直接命中；
- `idx_status_time(status_code, occurred_at)`：同理支撑「某时段 5xx/502/504」这类定障排查。

流水只追加不改写，查询模式固定是「时间窗 + 可选等值」三种，三个索引一一对应、没有多余索引拖累批量写入。

调用方在每个响应（含错误）上都能拿到 `X-Gateway-Trace-Id`，直接和流水的 request_id 对账。

## 第三方接入：应用凭据与来路名单

第三方要调进来，先在网关注册一个「应用」，网关发一对凭据：**应用编号 + 密钥**。
对方之后每个请求带头 `X-App-No: <应用编号>`、`X-App-Secret: <密钥>`，网关据此认人。
功能默认关闭，生产置 `APP_AUTH_ENABLED=true`（并配数据源），关闭时不装鉴权过滤器、也不读 `gw_app` 表。

库表 DDL：`src/main/resources/db/gw_app.sql`（应用表 `gw_app` + 来路名单表 `gw_app_origin`），
引擎 InnoDB / utf8mb4。

### 管理接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/gateway/apps` | 新建应用，**响应里一次性回显明文密钥** |
| GET | `/api/gateway/apps/{appNo}` | 应用详情（不含任何密钥字段） |
| GET | `/api/gateway/apps?pageNum=&pageSize=&keyword=` | 分页列表（编号/名称模糊，每行带 `originCount`） |
| PUT | `/api/gateway/apps/{appNo}/disable` | 停用（幂等） |
| PUT | `/api/gateway/apps/{appNo}/enable` | 启用（幂等） |
| GET | `/api/gateway/apps/{appNo}/origins` | 一次看清来路名单 |
| POST | `/api/gateway/apps/{appNo}/origins` | 加入一条来路（body `{"ip":"1.2.3.4"}`，幂等） |
| DELETE | `/api/gateway/apps/{appNo}/origins?ip=` | 删除一条来路（幂等；ip 走 query 以兼容 IPv6 冒号） |

- 应用编号业务唯一、建后不可改；重复建同号靠 `uk_app_no` 唯一索引原子拒绝（停用的也占号）。
- 创建人取请求头 `X-Created-By`（管理接口本身尚未鉴权，见「已知边界」）。
- 密钥有效期 `secretExpiresAt` 可空（空=长期有效），非空传 ISO-8601（与流水时间同口径，不带偏移按 UTC），且必须是未来时刻。
- 分页口径与路由列表一致：`pageNum` 从 1、`pageSize` 默认 20 上限 200，count 与取数在同一只读事务，数字严格对得上。

### 密钥安全（重点）

- 库里 `secret_hash` 存的是 **PBKDF2-HMAC-SHA256**（每应用独立随机盐、12 万次迭代）的不可逆散列，
  密钥由网关用 `SecureRandom` 生成 32 字节、URL 安全 Base64 编码。
- **明文密钥只在新建成功的响应里出现这一次**；列表、详情、日志、库里都不再有它，
  系统没有任何「查看原密钥」口子，我们自己后台/DBA 也无法还原。遗失只能重新签发。
- 校验用常量时间比对（`MessageDigest.isEqual`），不泄露密钥前缀。

### 来路名单（白名单）

- 每个应用可配一批来路地址，**只有名单内的来源才能用该应用凭据**；
  **名单一条都没配 = 不限制来源**（任何来源都能用）。
- 严格按 **IP 字面量精确匹配**，不做网段/前缀：配 `192.168.1.1` 绝不会放进 `192.168.1.10`；
  不收主机名、不收 CIDR（`1.2.3.0/24` 直接拒）。
- 入库与比对前都过 `ClientIpResolver.canonicalize` 归一成唯一字面量（IPv4 去前导零、
  IPv6 小写全写、支持 `::` 压缩与内嵌 IPv4），所以 `2001:DB8::1` 与 `2001:db8:0:0:0:0:0:1` 是同一条。
- 名单可单加、单删、一次看清；增删都幂等（`(app_no, ip)` 唯一键 + INSERT IGNORE）。

### 来源地址口径（绝不另起一套）

鉴权取来源地址直接用**全网关唯一实现** `ClientIpResolver`（鉴权、流水、限流共用），依次：

```
X-Forwarded-For 最左一个合法地址  →  X-Real-IP  →  传输层 remoteAddress
```

- 取最左一跳 = 调用链上最初的客户端，调用方多台机器轮询、中间隔着代理也认对来源；
- 头值必须逐段是合法 IPv4/IPv6 字面量才采纳（不做 DNS、不收主机名），伪造值整级跳过往后退，
  既不会「地址在名单里却进不来」，也不会「不在名单里反而放进来」。

### 鉴权结果（转发链路在匹配路由之前先认人）

| 场景 | HTTP | `X-Gateway-Error` |
| --- | --- | --- |
| 缺凭据、应用编号不存在、密钥错、密钥过期 | 401 | `APP_UNAUTHENTICATED` |
| 应用已停用 | 403 | `APP_FORBIDDEN` |
| 来源地址不在来路名单 | 403 | `APP_FORBIDDEN` |
| 鉴权配置此刻读不出来（库故障且无旧快照） | 503 | `APP_CONFIG_UNAVAILABLE` |

- 编号不存在与密钥错对外都是同一个 401 文案，避免枚举出哪些编号真实存在。
- 认证通过后，网关用**自己认定的规范化应用编号**覆盖入站 `X-App-No`，流水记的就是可信值。
- 鉴权在转发过滤器之前，凭据不过关不匹配路由、不打上游。

### 改完何时生效（说得清的边界）

- 应用与名单在内存里有一份鉴权快照（`AppCredentialCatalog`），请求只做 O(1) 查找 + PBKDF2，不每笔查库。
- **本实例**：任何停用/启用/名单增删的事务提交后发 `AppCredentialChangedEvent`，快照原子替换，
  **之后到达的下一笔新请求立即按新数据判定，没有宽限期**——停用不会让老凭据再用一阵，
  刚改完名单新请求立刻按新名单走（在途的旧请求沿用其到达时的判定，不被中途改写）。
- **多实例**：别的实例收不到进程内事件，靠 10s 定时兜底刷新（`apigw.app-auth.refresh-interval`）收敛。
- 库一时抖动：有旧快照就沿用旧快照继续服务并告警；**从未加载成功过时 fail-closed 回 503**，绝不裸放行。
- 密钥有效期按每笔请求的当前时刻判定，到点即拒，不需要额外操作。

## 用户登录鉴权与身份透传

网关在「调用方 → 上游」这一段上认用户身份：令牌由网关（或共享密钥的签发侧）签发，调用方带在 `Authorization: Bearer <jwt>` 里；网关验过之后，把「用户是谁、属于哪个租户」用固定头透传给上游，上游不必再自己解令牌。功能默认关闭，生产置 `USER_AUTH_ENABLED=true`。

### 开关（跟路由配置走）

- 全局开关：`apigw.user-auth.enabled`（环境变量 `USER_AUTH_ENABLED`），关闭时整套不装配，转发链路与没做这功能时一致。
- 路由开关：每条路由配置上的 `requireLogin`，只认：
  - `0`（默认）**开放路由**：谁都能打，没带令牌也照常放行；
  - `1` **必须登录路由**：必须带一张验签通过、没过期、信息齐全的令牌，否则 401。
- 两类路由混用同一条转发链路，是否校验由「这笔请求命中的那条路由」决定，开放路由的错误请求/无令牌请求绝不会走进内部路由的鉴权分支被拦。
- 标记跟着路由 JSON 一起存 Redis、一起热刷新；历史路由 JSON 里没这个字段时按 `0`（开放）解释。

### 令牌怎么验（不是拆开看字段，是真验签）

令牌是紧凑序列化的 JWT（`base64url(header).base64url(payload).base64url(signature)`），`header` 固定 `{"alg":"HS256","typ":"JWT"}`，`payload` 至少含 `sub`（用户标识）、`tenant`（租户标识）、`exp`（过期 epoch 秒）、`iat`（签发 epoch 秒）。网关每笔请求按顺序验：

1. **形状**：恰好三段、每段合法 base64url；
2. **算法钉死**：解出的 header 必须 `alg=HS256`、`typ=JWT`。`alg:none`、换算法名都拒——没有「算法协商」，杜绝改头部冒充无签名、公钥当 HMAC 密钥等经典伪造；
3. **真验签**：用配置的密钥对前两段重算 HMAC-SHA256，与第三段常量时间逐字节比较（`MessageDigest.isEqual`），差一个 bit 都拒。只把令牌拆开看字段、伪造签名、塞「永不过期」之类私字段，统统过不了；
4. **过期卡死**：`exp` 必须是数值，且判定为 `now >= exp` 即过期——**正好压在过期那一刻也算过期**，没有任何宽限钟（leeway）；
5. **声明齐全**：`sub` / `tenant` 必须是非空字符串；解出来的值还要过一次身份白名单（`[A-Za-z0-9._@=-]{1,256}`，挡住 CR/LF、空白、非 ASCII 的头注入值）。

任何一样不过（含压根没带、不是 Bearer 方案），内部路由统一回 **401 `TOKEN_UNAUTHENTICATED`**，对外文案不区分「没带/签名错/过期/缺声明」，内部原因只写服务端 debug 日志。

### 密钥只走配置，不硬编码

- 令牌密钥 `apigw.user-auth.token-secret`（环境变量 `USER_AUTH_TOKEN_SECRET`）；
- 网关戳密钥 `apigw.user-auth.stamp-secret`（`USER_AUTH_STAMP_SECRET`），留空时复用令牌密钥；生产建议分开——戳密钥要分发到各上游验真，令牌密钥不发；
- **没有内置默认密钥**：开关开了却没配、或密钥短于 32 字节，应用直接启动失败（fail-fast），不会退回一个写死在代码里的密钥。

### 身份透传（固定头，上游不用再解令牌）

验过之后，网关在发往上游的请求上写：

| 头 | 含义 |
| --- | --- |
| `X-Auth-User` | 用户标识（令牌里的 `sub`） |
| `X-Auth-Tenant` | 租户标识（令牌里的 `tenant`） |
| `X-Gateway-Stamp` | 网关盖的「这笔请求确实过了网关」的戳（见下） |

- **原始令牌不上递**：`Authorization` 头在网关验完即剥（鉴权过滤器剥一道、转发器头清洗里再钉死一道，双保险），上游只认身份头。
- 开放路由的匿名请求没有前两个头（上游据此区分匿名/登录），但一样盖戳（戳里身份字段为空串）。

### 防伪造（硬安全线：谁定的算数）

`X-Auth-User` / `X-Auth-Tenant` / `X-Gateway-Stamp` 是「网关说了算」的头。请求进来时，**无论命中哪条路由**，网关先把调用方自带的同名头一律剥光，再只按自己的验签/盖章结果写回。调用方在请求里塞再多假的身份头、假戳，到上游都会被清掉——上游看到的这三个头只有网关一个来源。

### 网关戳（上游可验真，伪造不出来）

`X-Gateway-Stamp` 让上游确认「这笔请求确实经过网关、且头没被中途改过」。头值：

```
v1.<unixEpochSeconds>.<base64url(HMAC-SHA256(canonicalString, stamp-secret))>
```

`canonicalString` 各字段以换行分隔，依次为：`v1`、时间戳、大写方法、应用内路径（原样）、URLEncoder 后的原始 query（无则空串）、`X-Gateway-Trace-Id`、用户标识（匿名空串）、租户标识（匿名空串）。上游用共享的戳密钥按同一规则重算一遍即可验真；调用方拿不到密钥，且戳与「方法+路径+query+trace+身份」绑定，截到一枚真戳也挪不到别的请求上。

### 跨域（前端读得到这些头）

CORS 过滤器排在鉴权之前：预检（OPTIONS）不带令牌也能在鉴权之前直接答复，不会被内部路由的 401 挡掉；正式响应回 `Access-Control-Expose-Headers`，显式放行 `X-Auth-User` / `X-Auth-Tenant` / `X-Gateway-Stamp`（外加 `X-Gateway-Trace-Id` / `X-Gateway-Error`）——浏览器 JS 默认只能读简单响应头，不在这里放行，前端读不到。允许来源由 `apigw.user-auth.cors-allowed-origins`（`USER_AUTH_CORS_ORIGINS`，逗号分隔）配置，默认 `*`（此时不带凭证），生产建议配成明确站点清单。

### 开放路由遇到坏令牌：放行、按匿名处理

口径统一为**放行但不赋予身份**。开放路由的契约是「令牌不是入场券」：浏览器/客户端常对同一域名的所有请求自动带上过期或无效的 Authorization（旧登录态、爬虫、探测请求），若因此回 401，公共页面会被一张过期令牌打成登录失效，和「没带令牌照常放行」自相矛盾。所以开放路由尽力识别身份——令牌有效就按登录用户透传，令牌缺失/损坏/过期（含「签名有效但身份声明写不进头」）都与「没带」同等对待，照常放行且不写身份头。坏令牌换不到任何身份，放行它没有额外权限代价。内部路由不适用这条：任何一点不过就是 401。

### 鉴权结果

| 场景 | HTTP | `X-Gateway-Error` |
| --- | --- | --- |
| 必须登录路由：没带令牌、不是 Bearer、签名错、过期（含正好到点）、声明不全/不合法 | 401 | `TOKEN_UNAUTHENTICATED` |
| 路由配置此刻读不出来（Redis 故障且无旧快照），无法判断要不要登录 | 503 | `TOKEN_CONFIG_UNAVAILABLE` |

### 过滤器顺序

```
AppAuthWebFilter        HIGHEST+5   第三方接入凭据（可选，先认「哪个应用」）
OrderedCorsWebFilter    HIGHEST+6   预检在此直接答复，不透到鉴权
UserTokenAuthWebFilter  HIGHEST+8   路由分流：要不要登录、验签、剥/写身份头、盖戳
GatewayProxyWebFilter   HIGHEST+10  复用已匹配路由，转发上游（再剥一道 Authorization）
```

## 配置怎么存

```
Redis key   apigw:routes          类型 Hash
            field = routeNo
            value = 该路由连同全部条件、动作的一整份 JSON
```

**为什么「一条路由 + 它的全部子项」塞在一个 field 里**：保存是一次 `HSET`、删除是一次 `HDEL`，Redis 单命令原子，所以「全落库或全不落」不需要手工回滚，也不可能读出主记录在、子项不在的残缺路由；删除时一次 `HDEL` 整树清掉，没有无主子记录可留。

## 并发怎么控

两层，都在 Redis 上：

1. **建路由占号用 `HSETNX`**：「查编号是否存在」和「写入」合成一个原子动作。两个人同时建同一个编号，只有一个成功，另一个收「编号已被占用（停用的路由也占号）」。
2. **改/删同一条用「短租约锁 + version 乐观锁」**：
   - 锁 key `apigw:lock:route:{routeNo}`，`SET NX` 带 5 秒 TTL，值是唯一 token，释放走 Lua 比对 token 后删除（不会误删别人的锁）；它把「读当前版本 → 写回」串成临界区。
   - 每条路由带 `version`：**修改必须显式带上读取时拿到的版本**（首版传 0），服务端比对一致才写、然后 version+1；不一致返回 `code=409`「你这份配置已经旧了（当前版本 n，你手上是 m），请重新拉取后再提交」。删除带 `expectVersion` 时有同样保护。
   - 不允许不带版本就改，否则等于把乐观锁绕过去、静默覆盖。

## 测试

```bash
docker compose up -d     # 提供真实 Redis
mvn test
```

- `GatewayRouteTest`：聚合不变量（编号不可改、上游地址、顺序号撞/跳并报位置、类型白名单、必填项、`requireLogin` 只认 0/1），无需 Redis。
- `GatewayRouteControllerWebTest`：HTTP 切片（真实 Controller + AppService + 聚合，mock 掉 Redis），覆盖统一返回、报错文案、分页数字与子项计数。
- `RouteStoreTest` / `GatewayRouteControllerIT`：连真实 Redis，覆盖 HSETNX 原子占号、并发建同号、乐观锁 409、整树替换与级联删除。本机探测不到 `localhost:6379` 时自动跳过（可用 `-Dredis.host/-Dredis.port` 指向别处）。
- `PathPrefixMatcherTest` / `RouteMatcherTest`：路径前缀边界（尾斜杠/段边界/大小写）、四类条件 AND、多命中稳定定序。
- `HeaderActionApplierTest`：补头覆盖同名值、删头彻底、顺序号先后、请求/响应方向隔离。
- `UpstreamFailureKindTest`：连不上=502、超时=504、能穿透异常包装层、异常链成环不挂死。
- `RouteCatalogTest`：快照缓存、变更事件即时生效、Redis 故障沿用旧快照、并发冷加载不打雷群。
- `GatewayProxyFilterTest`：真实 Netty 服务端 + 真实 WebClient 上游 + JDK HTTP 上游的端到端（无 Redis），覆盖方法/路径/查询/请求体转发、请求与响应头增删改、404/502/504 三态、报文绑定头不照抄、热刷新、traceId。
- `AccessLogRecorderTest`：进/出两段 traceId 串联、不串请求、异步不阻塞。
- `ClientIpResolverTest` / `GatewayHeadersTest`：来源地址取值顺序与合法 IP 校验（非法头逐级回退）、追踪号/应用编号白名单。
- `AsyncBatchingAccessLogSinkTest`：攒满即落、窗口超时落、关停 drain 不丢、关停等待封顶、队列满不阻塞不抛异常、写失败不杀写线程。
- `JdbcAccessLogRepositoryTest`：H2 真实 SQL——整批事务原子性（失败一条不留）、组合筛选、分页数字与稳定排序、毫秒精度。
- `AccessLogQueryServiceTest` / `AccessLogControllerWebTest`：护栏（时间窗/跨度/深翻页/每页封顶）、分页口径、时间串解析、统一返回。
- `SecretHasherTest`：PBKDF2 散列不可逆、随机盐、错密钥/损坏串安全判否。
- `ClientAppTest`：编号不可改、有效期边界、来路严格匹配与 IPv6 归并、空名单=不限、停用/过期/来路的拒绝分类。
- `JdbcAppCredentialRepositoryTest`：H2 真实 SQL——唯一索引原子拦重复编号、来路增删幂等、模糊分页与 originCount、快照读齐。
- `ClientAppServiceTest`：创建一次性密钥且库态无明文、开关幂等、名单增删只在真变更时发事件、分页护栏。
- `ClientAppControllerWebTest`：统一返回、创建响应一次性密钥且无散列字段、404/错误收口、来路增删。
- `AppAuthWebFilterTest`：真实 Netty 端到端——缺/错凭据与过期 401、停用/来路 403、XFF 多跳取值、`.1` 不放 `.10`、IPv6 写法归并、停用与名单改完对下一笔请求即时生效。
- `AppAuthEnabledSmokeTest` / `AppAuthDisabledSmokeTest`：开关开时整组 bean 装配且快照建立，关时一个都不装、上下文照常起。
- `JwtUserTokenTest`：真验签——假签名、他密钥签名、改载荷、`alg:none`/换算法/错 typ、坏 base64、缺 `exp`/`sub`/`tenant`、塞「永不过期」私字段全部识破；过期边界 `now == exp` 按过期、前一秒有效。
- `GatewayStampSignerTest`：用上游侧独立实现验戳，匿名/登录戳都验得过，换方法/路径/身份/trace/密钥均验不过。
- `UserAuthPropertiesTest` / `UserAuthMissingSecretFailFastTest`：默认关闭；开启却没配/密钥过短 fail-fast，不退回内置默认密钥；戳密钥缺省复用令牌密钥。
- `UserTokenAuthWebFilterTest`：真实 Netty——开放路由无令牌/坏令牌放行且无身份头、有效令牌带验签身份；内部路由无令牌/坏签名/他密钥/alg 伪造/过期（含压点）/缺声明/非 Bearer 全 401 且文案统一；伪造身份头/戳先剥后写；混用同链路不互相误伤；`/api` 不剥不盖；traceId 沿用。
- `UserAuthFullChainTest`：CORS + 登录鉴权 + 转发三过滤器串真实上游——上游只收验签身份与网关戳、收不到 Authorization，预检在鉴权前答复、响应放行三个透传头给 JS。
- `UserAuthEnabledSmokeTest` / `UserAuthDisabledSmokeTest`：开关开时整套（验签器/签发器/戳/CORS/鉴权过滤器）装配，关时无功能 bean、上下文照常起。
- `GatewayProxyIT`：真实容器 + 真实 Redis + 真实上游，建完路由立刻能转发、删完立刻失效；探不到 Redis 时自动跳过。

## 已知边界（留给后续题目）

- 管理接口（`/api/**`）本身未鉴权：`X-Created-By` 只是透传记录，接管理侧登录身份（谁能发凭据、改名单）是后续的题。
- 多实例间的配置即时一致目前靠 10s 定时轮询兜底（本实例内是事件即时）；要做到跨实例秒级一致可接 Redis Pub/Sub。接入凭据/名单同理。
- 密钥目前只在创建时发放，没有「重新签发/轮换密钥」接口；遗失或泄露后需要时再加（数据模型已留散列字段，换发即覆盖）。
- 鉴权被拒（401/403）的请求在匹配路由、转发之前就结束，因此不进 `gw_access_log` 流水表（也不产生文件式访问日志的 OUT 段）；若安全审计要统计「撞密钥/撞来路」的尝试，需要在鉴权过滤器内单独留一条拒绝审计。
- 用户登录令牌目前提供 `JwtUserToken.Signer`（容器内 bean，与网关共享令牌密钥的签发侧/运维工具可直接用），**还没有「登录换令牌」的对外接口**（校验账号密码、刷新、登出黑名单留给后续题目）；当前口径是令牌由共享密钥的签发方造好交给调用方。
- 用户/租户标识写进头前过白名单（ASCII 安全字符，≤256）；若用户标识含非 ASCII（如中文昵称），请在令牌里放 ASCII 的用户 ID 而不是展示名（HTTP 头本就不可靠承载非 ASCII）。
- 动作目前只支持请求/响应头的补与删；路径改写、查询串改写、体改写等留给后续。
