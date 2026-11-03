# AEMobileBridge

代理侧**通用命令桥**：后端 mod 把「以某个玩家身份执行一条代理命令」的请求发过来，本插件执行并把命令返回的聊天**只回给 App**，不发公屏。

目前 App 用它做 AllMusic 点歌（`/music`），但协议不绑定 music：以后要加其他代理命令（比如其它聊天/活动插件），把命令根加到 `allow-commands` 即可。

另外它也负责给 1.7.10 客户端下发 ServerUtilities 的现代 TAB 页眉页脚（`SU|TabHF` 信道），
玩家进子服时自动重发，客户端无需改配置。详见下文「TAB 样式下发」。

从 1.2.0 起还包含超长聊天守卫：玩家发出超过协议上限的聊天时，代理把内容拆成多条合法消息转发，
避免整条连接被关闭。详见下文「超长聊天守卫」。

## 部署

1. 把 `AEMobileBridge.jar` 放进 BC 的 `plugins/`；
2. 重启 BungeeCord（或重载插件）；
3. 编辑 `plugins/AEMobileBridge/config.yml`：

```yaml
bind: 127.0.0.1          # BC 和后端同机；分开部署时改 0.0.0.0 并加防火墙
port: 44445
secret: aemobile-bridge  # 必须和后端 AEMobile.cfg 的 [bridge] secret 一致
capture-ms: 800          # 默认收集窗口；搜歌这类异步命令由请求方传 captureMs=3500
allow-commands:
  - music
prefixes:
  - "[肥猫电台]"
  - "[AllMusic3]"
offline-permissions:
  - allmusic.play
  - allmusic.search
  - allmusic.vote
  - allmusic.list
  - allmusic.cancel
  - allmusic.mute
  - allmusic.push
tab:
  enabled: true
  # 玩家进子服后按这些毫秒数逐次下发；FML 握手完成前的自定义负载会被客户端丢弃，因此需要重发
  delays-ms: [2500, 5000, 8000, 12000, 18000, 26000]
  # 优先用子服 ServerUtilities 上报的 tab.headerText/footerText（AEMobile 3.5.1+ 会自动上报）
  prefer-server-config: true
  # 下面两项只是缺省/覆盖，内置默认值是中性的；真正的文案放在 SU 配置里
  header: "&b&l{ONLINE} &7位冒险者在线"    # 支持 \n 换行、& 颜色码
  footer: "&7本服: &b{SERVER}"
chat-guard:
  enabled: true
  # 单条消息的总长上限（UTF-16 字符数）；协议上限由客户端版本决定，1.7.10 为 100，1.11+ 为 256。
  # 客户端实际能发多少由客户端的 hodgepodge.cfg（longerSentMessages）决定，见「长度上限由客户端配置决定」
  max-length: 500
  # true 拆成多条发送；false 只截断到协议上限
  split: true
  # 是否向发送者提示处理结果
  notify: true
```

## 协议

TCP 一行一个 JSON：

```json
{"op":"exec","secret":"aemobile-bridge","player":"Steve","command":"music search 晴天","captureMs":3500}
```

返回：

```json
{"ok":true,"found":true,"online":false,"lines":["正在排队搜歌","搜索结果","1->晴天 | ..."]}
```

- `player` 必须是 `[A-Za-z0-9_]{1,16}`；
- `command` 去掉开头的 `/`，根命令必须在 `allow-commands` 里；
- `captureMs` 是命令返回后继续收集聊天的时间（100–10000ms）；
- 命令不存在：`ok:false, found:false, error:"UNKNOWN_COMMAND"`；
- 单玩家 1 秒 1 条、每分钟 30 条。

除 `exec` 外还有几个运维用的 op（同样要带 `secret`）：

| op | 作用 | 返回 |
|---|---|---|
| `tab_source` | **后端上报**：AEMobile 把本服 `serverutilities.cfg` 的 `tab.headerText/footerText` 发上来，带 `port`（本服 `server.properties` 端口）由代理反查子服名，也可直接传 `server` 指定；不带 `header`/`footer` 表示清除该缓存 | `{"ok":true,"server":"mgl","synced":N}` |
| `tab_sync` | 立刻给所有在线玩家重发一次页眉页脚 | `{"ok":true,"synced":N}` |
| `tab_status` | 查看开关、下发档位、当前生效的文案，以及各子服上报的原文 | `{"ok":true,"enabled":true,"delays":[...],"header":"...","footer":"...","hasOverride":false,"serverSources":{...}}` |
| `tab_set` | 临时覆盖文案，可传 `header` / `footer`；**只在内存里**，BC 重启失效，传空串表示清除 | `{"ok":true,"synced":N,"hasOverride":true}` |
| `tab_probe` | 故意发一段坏 JSON，客户端会打印 `Failed to parse SU|TabHF packet`，用来确认信道是否连通 | `{"ok":true,"probed":N,"payload":"..."}` |
| `chat_guard` | 查看超长聊天守卫的配置与计数；带 `"enabled":true/false` 时同时修改开关（只在内存，BC 重启失效） | `{"ok":true,"enabled":true,"maxLength":500,"split":true,"notify":true,"overLong":0,"splitMessages":0,"segments":0,"truncated":0,"rejectedCommands":0}` |

## TAB 样式下发

**文案只有一个来源：子服 ServerUtilities 的 `tab.headerText` / `tab.footerText`。**

- AEMobile 3.5.1+ 在玩家登录时反射读本服 `ServerUtilitiesConfig.tab.headerText/footerText`，
  通过 `tab_source` 上报给代理；内容没变不重复发。代理按子服缓存，玩家进服时下发。
- **不用在每台后端填子服名**（3.5.2+）：后端上报自己 `server.properties` 的 `server-port`，
  代理在 `servers:` 里按端口反查（如 25588 → `mgl`），命中时日志输出
  `tab_source: port 25588 -> backend 'mgl'`。端口未命中时退回手填的 `server`，仍未命中则对所有子服生效。
- 生效优先级：`tab_set` 运行时覆盖 → 子服上报值 → `config.yml` 的 `tab.header/footer` → 内置中性文案。
- 内置默认值刻意保持中性（只有 `{ONLINE}` / `{SERVER}`），仓库和 release 里不写具体服务器的
  群号或口号；要改文案请改 SU 配置，改完下一次有人登录就会上报（或手动发一次 `tab_source`）。
- 插件注册 `SU|TabHF` 信道，向 ServerUtilities 发 `{"header":"...","footer":"..."}`（UTF-8 JSON，
  `\n` 换行、`&` 颜色码由客户端转 `§`），文案里的 `{PLAYER}` / `{SERVER}` / `{ONLINE}` 会替换成当前值。
- `ServerConnectedEvent` 触发时客户端还在跑 FML 握手，握手期间的自定义负载会被客户端丢弃，
  所以按 `delays-ms` 阶梯重发；切服或断线后自动停止，不会向旧连接发送。
- **客户端要求**：GTNH 整合包自带的 ServerUtilities，外加 [AEMobile](https://github.com/Huaxidesu/AEMobile)
  3.5.0+ 的后备钩子（3.6.1 起还会自动处理下述冲突）。

### TAB 重叠 / 页眉页脚不显示排查

按 TAB 出现**两份列表叠在一起**，或页眉页脚不显示，本质都是三方抢同一个渲染事件
（`RenderGameOverlayEvent.Pre` 的 `PLAYER_LIST`）：

| 参与方 | 行为 |
|---|---|
| NecroTempus 的 PlayerTab | `PlayerTabEnabled=true` 时每帧先 `setCanceled(true)` 再自己画一份（无页眉页脚） |
| AEMobile `TabOverlayHook`（3.5.0+） | 事件被抢占时撤销 cancel，直接调 SU 的 `ModernTabRenderer.render()` |
| ServerUtilities mixin | 画带页眉页脚的现代 TAB |

- NecroTempus 与 AEMobile 钩子同时在场 → Necro 画一份、SU 画一份 = **重叠**；
- 只有 NecroTempus 在场（没装 AEMobile）→ SU 的渲染器永远执行不到 = **页眉页脚不显示**。
- **修复**：客户端 `config/NecroTempusConfig.cfg` 里 `B:PlayerTab=false`；
  AEMobile **3.6.1+** 客户端检测到 SU 时会在启动阶段自动停用它（日志搜
  `检测到 NecroTempus 的 PlayerTab 已开启`），配置文件丢了也不会复发。
- `BetterPingDisplay` 是同类冲突：在同一事件里 cancel 后整份重画原版 TAB，直接覆盖 SU 的渲染，
  移除或禁用即可。
- 排查这类问题先对比「正常的实例」和「异常的实例」的 mods 哈希与 `config/` 差异，不要急于修改代码。
  实际案例：玩家导出包与测试实例的 mods 完全一致（含同 sha256 的 AEMobile 客户端包），
  唯一差异是导出包缺 `NecroTempusConfig.cfg`、开关走默认 `true`，于是只有导出包重叠。

## 超长聊天守卫

1.7.10 客户端发出超过 100 个 UTF-16 单元的聊天时，代理会直接关闭整条连接，玩家表现为「连接已丢失」，
服务端日志只有一句 `lost connection: Disconnected`。原因是 `UpstreamBridge.handle(Chat)` 在构造
`ChatEvent` **之前**执行长度校验，超限抛出 `IllegalArgumentException`，`HandlerBoss.exceptionCaught`
随即断开连接。

这个位置决定了可选的实现方式：

| 位置 | 能否阻止断线 | 原因 |
|---|---|---|
| 代理管道（本插件，`inbound-boss` 之前） | 能 | 校验发生在更靠后的处理器里，此处可以改写消息 |
| 子服 Forge 模组（含 GTNHChat） | 不能 | 代理在转发之前就断开了，子服收不到这条消息 |
| 代理插件监听 `ChatEvent` | 不能 | 校验在事件构造之前 |

**做法是拆分，不是改走自定义通道。** 每段都是合法的 `C01PacketChatMessage`，子服的禁言、过滤、
聊天记录以及其他模组的 `ServerChatEvent` 全部照常生效。改走自定义负载通道（如 GTNHChat 的
`gtnchat` 信道）可以做到内容无损，但会绕过 `NetHandlerPlayServer.processChatMessage` 的全部治理逻辑，
被禁言的玩家只需改用长消息即可发言。

行为说明：

- 上限按客户端协议版本判定，与代理的校验保持一致：协议 315（1.11）起为 256 字符，之前为 100。
- 总长超过 `chat-guard.max-length` 的部分截断，不会发送。
- `chat-guard.split: true` 时拆成多条发送；`false` 时只截断到协议上限（用于对照验证）。
- 以 `/` 开头的消息不拆分：拆开后第二段起会被子服当作普通聊天执行。这类消息不转发，
  并向发送者输出「命令超过 N 字符，未发送」。
- 拆分按 UTF-16 单元进行，末位是高位代理字符时回退一位，不会切出孤立的代理字符（emoji 每个占两个单元）。
- 拆分后除首段外的各段不以 `/` 开头：代理会把 `/` 开头的消息交给 `dispatchCommand` 执行，
  玩家可以在超长消息的切分点后植入命令。遇到这种情况会向前收窄切点（内容不变），
  连续斜杠占满整段时才把一个斜杠换成全角。
- 发送者会收到一条提示（`chat-guard.notify: false` 可关闭）。其他玩家看到的是连续的 N 条消息，
  **目前不会合并回一条**；需要合并时再考虑代理与子服协同的方案。

### 长度上限由客户端配置决定

客户端能发出多少字，由客户端自己的配置决定，代理无法改变；代理侧只能决定「收到后怎么处理」。

| 位置 | 配置项 | 作用 |
|---|---|---|
| 客户端 | `config/hodgepodge.cfg` → `tweaks.B:longerSentMessages` | GTNH 默认 `true`。Hodgepodge 的 `MixinC01PacketChatMessage_LongerMessages` 把 `C01PacketChatMessage` 构造器的截断值由原版 100 改成 **256**，`false` 时回到原版 100。GTNH 2.9.0 的该项是布尔值，**只有这两档**，没有可填数字的选项 |
| 子服 | `config/gtnchat/config.yml` → `chat.max-length` | 数字，默认 256。GTNHChat 在服务端成型聊天时按此截断；客户端上的同名文件只影响单机，不影响发往代理的长度。调到小于单段长度时该段会被截掉 |
| 代理 | `plugins/AEMobileBridge/config.yml` → `chat-guard.max-length` | 数字，默认 500。总长超过此值的部分不发送 |

三者关系：

- 客户端上限（100 或 256）先起作用。超出的字符在客户端就被截掉，到不了代理，代理也补不回来。
  想让玩家发更长的内容，只能改客户端：`B:longerSentMessages=true`（256）已是 GTNH 提供的最大值。
- `chat-guard.max-length` 只在小于客户端上限时才真正生效。GTNH 默认客户端为 256，
  线上实际形态是 256 字符拆 3 段（100 + 100 + 56），默认 500 是给「客户端被改过、
  或装了别的聊天 mod」的情况留余量，默认配置下不会触发截断。
- 要严格限制长度，把 `chat-guard.max-length` 调到目标值（例如 200），超出部分由代理截断并向发送者提示；
  要让消息完整送达，保持它不小于客户端上限即可。
- `chat.max-length` 应大于单段长度（100），否则子服会在 GTNHChat 成型时再截一次。

生效方式：改客户端配置后重启客户端；改 `chat-guard.max-length` 后重启代理
（`chat_guard` op 只能在运行时切换 `enabled`，不改其它项）。实测 99 字符不介入，
101 字符拆 2 段，256 字符拆 3 段。

排查与验证：

1. 服务端启动日志出现 `chatGuard=true` 表示配置已加载；
2. 玩家登录后发一条超过 100 字符的聊天，应正常发出且不掉线；
3. 通过 `chat_guard` op 查看计数：`overLong` 为超长消息数，`splitMessages` 为拆分次数，
   `segments` 为总段数，`rejectedCommands` 为被拒绝的超长命令数；
4. 若计数未增长，说明玩家连接的管道里没有装上守卫，检查日志中是否有 `聊天守卫注入失败`。

`chat-guard.enabled: false` 时守卫不介入，保持 1.1.x 的行为（超长消息照旧断线），用于快速回滚与对照。

## 离线玩家

- 用虚拟 `CommandSender`（名字 = 请求里的玩家名）执行，权限按 `offline-permissions` 放行。
- 具体命令的行为由目标插件决定。以 AllMusic 为例：`help/search/list/vote/cancel/mute` 离线可用；
  `select`/`music <id>` 需要服务器**有音乐会话**（AllMusic 客户端在线），否则 AllMusic 自己返回
  “没有播放的玩家”，App 会转成友好提示。

## 构建

需要 Java 8 和 BC 的 API jar（默认取本机 HexaCord）：

```powershell
$env:JAVA_HOME="D:/Library/DevelopmentKit/Java/Zulu/zulu8"
./build.ps1
```

产物：`AEMobileBridge.jar`，复制到 BC `plugins/`。
