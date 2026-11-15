# Legado 书源接入与实测记录

> 数据来源：用户提供的 `Read最新2000+书源合集(3).json`（2640 个源，Legado / 「阅读 3.0」格式）。

## 支持范围（当前实现）

`data/source/legado/`

- `LegadoRule`：规则翻译
  - 选择器 `class.xxx` / `id.xxx` / `tag.xxx` / 原生 CSS
  - 链式下钻 `class.list@tag.a@href`，索引 `tr.0`、`a.1`、`td.-1`
  - 取值 `text` / `ownText` / `html` / `all` / `href` / `src` / 任意属性（`title`、`alt` 等按属性取，不再当 URL 解析）
  - 兜底 `||`，后处理 `##正则` 与 `##正则##替换`
  - 占位符 `{{key}}` / `{{page}}` / `{{page-1}}` / `{{page+1}}` / `{{baseUrl}}`
  - `bookSourceUrl` 上的 `#标签` / `##标签` 会剥掉
- `LegadoSource`：search / details / chapters（含 `tocUrl`）/ content
  - 默认带 `Referer`（站点根）与 `Accept-Language`，源自带 `header` 会覆盖
- `LegadoImporter`：导入 JSON（对象或数组），原样保存每个源的 JSON，便于后续扩展

**不支持（会返回空/抛错，界面提示换源）**：`@js:`、`<js>`、`@get:{变量}`、`&&` 合并、`%%`、
`searchUrl` 的 POST/body、登录与 Cookie 流程、`exploreUrl`（发现页浏览）。

## 实测（2026-11-15，模拟器 + 本机网络）

用 `LegadoProbeTest` 巡检前 120 个「小说」源（关键词「龙族」，并发 10，单源 20s 超时）：

| 结果 | 数量 | 说明 |
| --- | --- | --- |
| 有结果 | **2** | 稻草书屋（18 条）、文本小说（100 条） |
| 失败 | 118 | 见下 |

失败原因分布：

- HTTP 403（站点 WAF 拦 UA/Referer）：樱下书院、笔下文学等
- 规则用了 JS / `@get:` / `<js>`：四库书屋、换源小说、阅读助手等
- 连接超时 / reset：轻之文库、机翻机器人等（网络抖动，重试可能成功）

> 结论：这批源偏「中文站 + 反爬 + JS 规则」，纯 HTML 兼容层只能覆盖一小部分。
> 想大幅提高命中率，下一步要么补 `@js:`（需要 JS 引擎），要么针对可用站点单独写 `WebSourceConfig`。

## 复现巡检

工程路径含中文会让 Gradle 单测 worker 报 `ClassNotFoundException`，所以先用联接（Junction）映射到 ASCII 路径：

```powershell
cmd /c mklink /J D:\tsundoku_build "D:\CRPEER\编程\Tsundoku"
$env:TSUNDOKU_SOURCES="E:\code\Android\tmp\sources.json"
$env:TSUNDOKU_KEYWORD="龙族"
$env:TSUNDOKU_LIMIT="120"
$env:TSUNDOKU_REPORT="E:\code\Android\tmp\probe-report.txt"
cd D:\tsundoku_build
.\gradlew.bat :app:testDebugUnitTest --tests "app.tsundoku.reader.LegadoProbeTest"
```

不设 `TSUNDOKU_SOURCES` 时只跑离线规则用例（`LegadoRule` 的四个断言），可以安全放进 CI。

## 调试入口

`adb shell am start -n app.tsundoku.reader/.MainActivity --es q 龙族`
直接打开全局搜索并自动搜索（模拟器命令行无法输入中文，用它来测）。
