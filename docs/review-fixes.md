# 代码审查问题核对与修复记录

> 基线提交：`6ad5d52`（main）。修复落在分支 `feat/legado-typography-storage`（PR #1）。
> 核对方式：先读代码确认，再写单元测试复现，最后改实现并跑测试。

## 一、P0：基础正确性与数据安全

### 1. RuleParser 文本与 URL 混淆 —— 确认存在，已修复

- **现象**：`extract()` 结尾无条件 `resolveUrl(raw, baseUrl)`。中文因为不是合法 URI 会抛异常回退到原值，
  但纯 ASCII 文本（`anonymous`、`Dragon-Raja`）会被拼成 `https://site/book/anonymous`。
- **修复**（`data/source/web/RuleParser.kt`）：
  - `text/ownText/html/outerHtml` 永不补全
  - `href/src/poster/data-src/action` 必定补全
  - `content` 仅在值形如路径（`/`、`http`、`./`）时补全
  - 只允许 http/https 结果，`javascript:` 直接丢弃
  - 顺带：`select()` 也支持 `||`（原来只有 `selectFirst()` 支持，行为不一致）
  - 顺带：支持 `div.item@a@href` 链式下钻（之前 `lastIndexOf('@')` 会把整段当选择器）
- **测试**：`RuleParserTest`（11 条，含中文/ASCII 文本、href/src、`||`、坏选择器）

### 2. EPUB 解压路径安全 —— 确认存在，已修复

- **现象**：`target.path.startsWith(destDir.canonicalPath)` 是字符串前缀比较，
  `/data/books/x-evil` 会被判为在 `/data/books/x` 内；且没有条目数/体积上限，失败不清理。
- **修复**（`data/import/EpubImporter.kt`）：
  - 改用 `Path.normalize()` + `Path.startsWith(rootPath)` 判断
  - 拒绝绝对路径条目（`/x`、`C:`）
  - 上限：条目 ≤ 5000、单条目 ≤ 128 MB、解压总量 ≤ 512 MB（流式计数，防 zip 头谎报）
  - 任何异常都 `deleteRecursively()` 清理目标目录
  - OPF 内的资源路径同样走 `resolveInside`
- **测试**：`EpubImporterTest`（6 条：正常解包、`../` 越界、绝对路径、条目过多、超体积、非 EPUB）

### 3. 数据库破坏性迁移 —— 确认存在，已修复

- **现象**：`AppContainer` 用 `fallbackToDestructiveMigration()`，未来缺迁移会直接清库。
- **修复**：
  - `data/local/DatabaseMigrations.kt`：迁移登记表；`MIGRATION_1_2` 给 `books` 加
    `lastPositionOffset`（章节内位置）
  - `AppDatabase.VERSION = 2`，`exportSchema = true`
  - `AppContainer`：去掉破坏性迁移，改为 `.addMigrations(*DatabaseMigrations.ALL)`；
    缺迁移时 Room 抛异常而不是删库
  - `data/local/DatabaseBackups.kt`：**升级前**把整库（含 `-wal`/`-shm`）复制到
    `databases/backup/`，最多留 5 份
- **实测**：模拟器上带 v1 数据升级到 v2，书架 5 本书与进度全部保留，
  `databases/backup/tsundoku-v1-20261115-053925.db` 生成成功。
- **测试**：`DatabaseBackupsTest`（6 条，含「每一步版本都有迁移」的守卫测试）

### 4. 更新目录丢失阅读数据 —— 确认存在，已修复

- **现象**：`refreshChapters()` 先 `dropChaptersFrom(book.id, 0)` 再整批插入
  `content = null, read = false`，缓存正文、已读状态、进度全丢。
- **修复**：
  - `data/repo/ChapterMerge.kt`：按 **URL** 匹配旧章节（老数据兜底用「标题 + 位置邻近」），
    保留 `content` / `read` / 原行 id，处理新增/删除/改名/重排，并把阅读进度映射到新下标
  - `LibraryRepository.syncChapters()`：在一个事务里「读旧 → 合并 → 覆盖 → 修正进度」
  - `BookDetailScreen.refreshChapters()` 与 `UpdatesRepository` 都改走它，
    UI 会显示「新增 N / 移除 N / 改名 N」
- **测试**：`ChapterMergeTest`（8 条：保留缓存、追加、重排、删除、改名、空目录不清库、
  无 URL 兜底、进度越界钳制）

## 二、阅读器

### 5. 正文分页 —— 已实现

- `WebSourceConfig.ContentRule` 新增 `next_page`（下一页规则）与 `max_pages`（默认 8）
- `data/source/web/ContentPaginator.kt`：跟随 `nextContentUrl` 抓完整章，防护：
  URL 去重（防环）、最大页数、同主机限制、仅 http(s)、单页失败保留已抓内容
- `WebSource.content()` 与 `LegadoSource.content()` 都改走分页器；
  章节导航语义不变——**一章的所有页合并完才进下一章**
- **测试**：`ContentPaginatorTest`（8 条：合并、环、上限、跨域、失败降级、空页、非法 scheme、同主机判定）
- **未做**：只支持静态规则的 `nextContentUrl`，`@js:` 生成的分页地址不支持

### 6. 阅读进度 —— 已改进

- 新增 `books.lastPositionOffset`（v2 迁移），读取时记住章节内位置
- 打开章节时恢复：滚动模式恢复 `scrollY`，翻页模式恢复页码（换字号/主题重载也不跳回开头）
- **不再在打开章节时就标记已读**：改为「读到本章末尾」或「点下一章」时才 `read = true`
- 位置保存：滚动/翻页 700 ms 防抖落库；返回键先 `flushPosition()` 再退出；
  `onCleared()` 用应用级作用域兜底写一次；同时同步 `meta.json`
- **实测**：翻到第 3 页 → 退出 → 重进，回到第 3 页（`lastPositionOffset = 2`）
- **未验证**：强杀进程的极端场景（`onCleared` 不保证执行），防抖窗口内仍有极小概率丢几十毫秒位置

### 7. 空章节与加载异常 —— 已修复

- `ReaderViewModel.init` 用 `withTimeoutOrNull(8s)` 等书与章节，超时/为空都落到
  `ChapterContent.Failure(可读原因)`，不再无限转圈
- 新增 `Failure` 状态 + 「重试」按钮；来源不可用、规则不匹配、正文为空都有具体文案
- 阅读器 WebView 拦截外链跳转（`shouldOverrideUrlLoading` 返回 true）

## 三、书源系统

### 8. 分类浏览 —— 已修复

- `WebSource.browse(url, page)`：**不再忽略传入的 url**（原来点任何分类都请求固定首页）
- `BookSource.categories()` 新接口；`WebSourceConfig.categories` 支持配置分类
- `LegadoSource`：解析 `exploreUrl`（文本分组格式与 JSON 数组格式），
  `browse()` 优先用 `ruleExplore`，缺失时回退 `ruleSearch` 规则
- `SourceBrowseScreen` 顶部显示分类 chip，分页时没有 `{{page}}` 占位符就直接停止，避免重复请求
- **测试**：`LegadoExploreTest`（4 条）
- **限制**：`exploreUrl` 里用 `@js:` 动态生成的分类会被跳过

### 9. Legado 兼容 —— 分阶段，当前进度

已支持：CSS/`class.`/`id.`/`tag.`、索引 `a.1`/`td.-1`、`@text/@ownText/@html/@all/@属性`、
`||`、`##正则##替换`、`{{key}}/{{page}}/{{page-1}}/{{baseUrl}}`、`tocUrl`、
`nextContentUrl`、`exploreUrl`、自定义 `header`、Referer/Accept-Language 默认头。

未支持（明确跳过而不是假实现）：`@js:` / `<js>` / `@get:` / `@put:` / `&&`、POST 搜索、
登录与 Cookie 持久化、`concurrentRate` 限速。

### 10. 书源诊断 —— 已实现

- `data/source/SourceDiagnostics.kt`：搜索 → 详情 → 目录 → 正文 逐段执行，
  每段记录状态、耗时、结果摘要或具体错误（含 HTTP 码与 URL），前一段失败会跳过后续并说明原因
- 入口：更多 → 管理来源 → 每个来源的 ▶ 图标
- **实测**：对「笔下文学」诊断，输出 `搜索 967 ms ✕ HTTP 403: https://m.fyyhm.org/search.php?keyword=...` +
  `详情 / 目录 / 正文 – 搜索失败，后续跳过`

## 四、本地导入

### 11. TXT —— 未修复（记录问题与方案）

- 现状：`TxtImporter.readText()` 用 `file.readBytes()` 一次性读入，大文件（>50 MB）有内存风险。
- 未改原因：需要把「编码嗅探 + 章节切分」改成流式管道，属独立改造，本轮不动以免引入回归。
- 建议：按 4 MB 分块读取 → 用 BOM/UTF-8 校验/GBK 逐块解码 → 章节切分改为增量状态机。

### 12. PDF —— 部分修复（记录问题）

- 现状：`PdfImporter.inspect()` 用 `runCatching{}.getOrElse { pageCount = 0 }` **吞掉异常**，
  损坏的 PDF 会被当成「导入成功但 0 页」。
- 本轮改动：`BookImporter` 现在会在 `pageCount == 0` 时……（未做，仍按原行为导入，
  阅读器打开时显示「无法打开这个 PDF 文件」）。
- 待办：`inspect()` 应返回 `Result` 或在 `pageCount <= 0` 时抛 `IOException`，让导入失败可见。

### 13. EPUB —— 部分改进

- 已修：路径安全、体积/条目上限、失败清理（见第 2 点）
- 已确认正常：spine 顺序、manifest 资源、cover 优先级（`meta[name=cover]` → `properties=cover-image`）
- 已知不足：`linear="no"` 的条目也会被当成章节；书名直接取 `dc:title`，
  章节名直接取 XHTML `<title>`（像 `149228` 这类书标题就会很难看）

## 五、工程质量

### 14. 自动化测试

新增 JVM 单测 47 条：

| 文件 | 覆盖 |
| --- | --- |
| `RuleParserTest` | 文本/URL 语义、`||`、链式、坏规则 |
| `EpubImporterTest` | 路径穿越、上限、清理、正常解包 |
| `DatabaseBackupsTest` | 升级备份、侧车文件、保留份数、迁移完整性 |
| `ChapterMergeTest` | 目录同步的数据保留 |
| `ContentPaginatorTest` | 正文分页的合并与防护 |
| `LegadoProbeTest` | 离线规则用例 + 可选联网巡检 |

> 注意：工程路径含中文时 Gradle 单测 worker 会报 `ClassNotFoundException`，
> 需要 `mklink /J D:\tsundoku_build "D:\CRPEER\编程\Tsundoku"` 后在该路径运行。

### 15. 代码结构

- 把「合并逻辑」「分页逻辑」「备份逻辑」抽成纯函数/纯类，可直接单测
- `LibraryRepository` 通过构造参数注入事务执行器，避免直接依赖 `AppDatabase`
- 仍然存在的问题（未改）：`BookDetailScreen` 的 ViewModel 里混着加入书架/刷新目录等业务编排；
  `NetworkClient` 用的是阻塞 `execute()`，协程取消不会中断请求
