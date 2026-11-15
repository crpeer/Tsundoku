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

### 5b. 翻页模式页码与正文错位 —— 已修复（真机复现并验证）

现象：左右翻页时页码从「第 11 页」变成「第 12 页」，但正文一动不动；章节较长或空行/分节标题多时更明显，末尾还会翻出空白页。

排查结论（都是实测，不是推测）：

1. **不是**「重组导致重复加载」——加日志统计「打开 1 次 + 翻 5 页」只发生 1 次 `loadDataWithBaseURL`。
2. 真正的根因是**单位混用**：`WebView.contentHeight` 是 **CSS 像素**，`scrollY` / `width()` 是**物理像素**。
   实测 `webView=720×1092 @1.75`，而 `innerWidth=clientWidth=1216`、`contentHeight=1313`（1216×1.75≈2128、1313×1.75≈2298）。
   旧实现用 `column-width = 视口dp - 2*padding` 算列宽、用 `web.width` 当翻页步长，
   两者根本不在一个坐标系里，于是「页码在涨、正文不动」，滚过最后一列还会出现空白页。
3. `contentHeight` 在 `onPageFinished` 时经常是 **0**；`scrollTo(0, 1e7)` 也**不会**同步夹紧（实测读到 13104）。

修复：翻页改成「按视口高度整页纵向滚动」，完全不用 CSS 多栏。

- `ui/reader/PagerMath.kt`（新增，纯函数）：`pageCount` / `pageOf` / `pageOffset` / `targetPageScroll` / `ratio`，
  全部只吃物理像素，输入是**实测的 maxScroll**
- `ReaderWebView`：`maxScroll = computeVerticalScrollRange() - computeVerticalScrollExtent()`
  （与 `scrollY` 同为物理像素）；布局稳定前 range==extent，所以 `onPageFinished` 后再补测一次（400 ms）
- 页码 = `scrollY / 视口高`，最后一页固定落在 `maxScroll`，WebView 自己夹紧范围 ⇒ **不可能出现空白页**
- `cssFor()` 翻页分支不再输出 `column-*`，并加 `pagedGuard` 用 `!important` 压住 EPUB 自带的
  固定宽度/分栏/固定高度，只保留纵向滚动
- `AndroidView.update` 增加 `loadKey`（正文+排版+视口）判断：只有它变了才 `loadDataWithBaseURL`，
  普通重组不得重排；`beginLoad()` / `restorePass` / `settleRestore()` 保证还原完成前不上报位置，
  避免「刚载入时的 0 位置」把记住的相对位置覆盖掉
- 章末翻页：`turnPage()` 返回 false 才切章；往前翻回到上一章时用 `initialRatio = 1f` **落在章末**
- 顺手修掉：设置/目录面板打开时返回键无效（`BackHandler(enabled = !showSettings && !showToc)`）

测试：

- `PagerMathTest`（9 条）：页码跟随滚动、最后一屏必须单独成页、遍历各种 maxScroll 断言
  「一路翻到底正好停在 maxScroll」「每次只前进一屏」「不会翻过头」
- `ReaderCssTest`（5 条）：翻页 CSS 不得出现具体列宽/列距、必须保留纵向滚动、不得固定页高
- `TxtImporterTest` 新增 2 条：只有全角空格/不换行空格/制表符的行不算段落；一行一段

真机验证（720×1600 @280dpi，TXT 2000 章 / EPUB 98 章 / 压力样例）：

| 场景 | 结果 |
| --- | --- |
| big-book（TXT）第 8 章 | 1/3 → 2/3 → 3/3，每页正文都不同，3/3 是最后一行且不裁切 |
| 3/3 再往后翻 | 进入第 9 章 1/3 |
| 第 9 章 1/3 往回翻 | 回到第 8 章 **3/3** |
| 字号 19→29px | 页数 3→5，位置按比例保留（第 2/3 页 → 第 3/5 页） |
| 退出重进 | 回到离开时的章/页 |
| EPUB 149228 第 1 章 | 1/11 → 5/11 → 11/11 → 第 2 章 1/10，无空白页 |
| TXT 单个 3000 字长段落 | 16 页，逐页内容连贯 |
| 连续 5/8 个空行 | 空行被丢弃，不留大段空白 |
| 只有全角空格/nbsp 的 20 行 | 只剩一段正文，1 页 |

**已知取舍**：因为按整屏滚动分页，页与页之间可能在屏幕顶端出现半行残字（滚动分屏的固有现象，
不是漏字也不是重复）。要彻底消除需要按行盒测量排版（类似 Readium），本轮不引入。

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

### 11. TXT —— 已改为流式导入

- 编码：只用**文件头 64 KB 样本**探测（BOM → 严格 UTF-8 → GBK），不再 `readBytes()` 整本读入
  - 踩坑：一开始用 `sample.size < SAMPLE_BYTES` 判断「尾部截断」，导致小于 64 KB 的 GBK 文件被误判成 UTF-8；
    现改为「去掉末尾 1–3 字节再试解码」
  - BOM 残留：UTF-8 BOM 会让首行变成 `\uFEFF第一章` 导致标题不匹配，已在该行做 `trimStart('\uFEFF')`
- 切分：`BufferedReader` 逐行读，内存里只保留**当前章节**；章节标题正则改为逐行 `matches()`
- 写入：按 20 章一批回调，`BookImporter` 边收边写库（先建书记录，失败则连同空目录一起删掉）
- 测试：`TxtImporterTest` 10 条（UTF-8/GBK/BOM/无章节/空文件/批量/CRLF/空行/转义/旧接口）
- 实测：模拟器导入 2.71 MB、2000 章的 TXT，最终库里正好 **2000 章**（idx 0–1999）

### 12. PDF —— 已改为「失败要报错」

- `PdfImporter.inspect()` 不再 `runCatching{}.getOrElse { pageCount = 0 }`：
  空文件 / 打不开 / `PdfRenderer` 构造失败 / 0 页，都会抛 `IOException` 并带上原因
- `BookImporter.importPdf()` 遇到 `pageCount <= 0` 也会拒绝导入
- 实测：推送一个 12 字节的假 PDF，导入提示
  **「导入失败：PDF 解析失败，可能已损坏或加密：Unable to load the document!」**，书架没有多出任何书
- 仍未处理：超大 PDF 的渲染内存（每页 bitmap ≈ 5 MB，Pager 同时保留 1–3 页），暂未做 LRU

### 13. EPUB —— 元数据兜底

- 书名：`dc:title` 是纯数字 / 全是分隔符 / 与文件名相同 / 含 untitled·未命名 时，回退到文件名
- 章节名：`<title>` 与 `h1–h4` 都不可用时，统一给「第 N 章」
  （原来这类书会显示 `1`、`2`、`3`；实测 `149228.epub` 现在显示 `第 1 章`、`第 2 章`…）
- spine：`linear="no"` 的条目（脚注/版权页）不再进阅读顺序；若全书都是 no 才全部保留
- 封面：manifest 里没有 cover 时，退一步用第一个 `image/*` 资源
- 踩坑：判「垃圾标题」最初用了 `^[\d\s\W_]+$`，Java 的 `\W` 对中文恒为真，
  会把「龙族」这类中文书名当垃圾；已改成显式的 `^[\d\s+_.\-]+$`
- 测试：`EpubImporterTest` 共 9 条（含纯数字书名、纯数字章节名、全垃圾章节名、`linear=no`）

## 五、工程质量

### 14. 自动化测试

新增 JVM 单测 **57 条**：

| 文件 | 覆盖 |
| --- | --- |
| `RuleParserTest` | 文本/URL 语义、`||`、链式、坏规则 |
| `EpubImporterTest` | 路径穿越、上限、清理、正常解包、书名/章节名兜底、`linear=no` |
| `TxtImporterTest` | UTF-8/GBK/BOM 识别、流式分批、CRLF、空文件、HTML 转义 |
| `DatabaseBackupsTest` | 升级备份、侧车文件、保留份数、迁移完整性 |
| `ChapterMergeTest` | 目录同步的数据保留 |
| `ContentPaginatorTest` | 正文分页的合并与防护 |
| `LegadoExploreTest` | `exploreUrl` 解析 |
| `LegadoProbeTest` | 离线规则用例 + 可选联网巡检 |

> 注意：工程路径含中文时 Gradle 单测 worker 会报 `ClassNotFoundException`，
> 需要 `mklink /J D:\tsundoku_build "D:\CRPEER\编程\Tsundoku"` 后在该路径运行。

### 15. 代码结构

- 把「合并逻辑」「分页逻辑」「备份逻辑」「编码探测」抽成纯函数/纯类，可直接单测
- `LibraryRepository` 通过构造参数注入事务执行器，避免直接依赖 `AppDatabase`
- `TxtImporter` 改成「回调式流式 API」，`BookImporter` 只负责建书 + 分批写库
- 仍然存在的问题（未改）：`BookDetailScreen` 的 ViewModel 里混着加入书架/刷新目录等业务编排；
  `NetworkClient` 用的是阻塞 `execute()`，协程取消不会中断请求

## 六、Readers 功能对齐（参考 Readest / Legado / Mihon）

### 批次一：分页引擎 + 翻页交互 —— 已完成

上一版用「按视口高度整页滚动」分页，页边界会把最后一行切成两半（页 1 显示半行、页 2 再完整显示一次）。
现在改为 **CSS 多栏 + 根元素横向滚动**（与 Readest / foliate-js 相同）：

- `ui/reader/ReaderScript.kt`：注入脚本，负责分栏、测量、翻页、点击区域、手势
- `ui/reader/PaginationBridge.kt`：`@JavascriptInterface` 桥，只暴露整数/布尔参数的方法
- 多栏必须设在 `documentElement` 上；设在 body 或普通 `div` 上时列溢出会被裁掉
  （实测 `scrollWidth` 恒等于视口宽，`pages` 恒为 1）
- 视口尺寸由 Kotlin 侧量好写进 `viewport meta` 与脚本：实测同一设备上
  `window.innerWidth` / `documentElement.clientWidth` 会给出 **412 与 1628** 两种值，
  导致整章被判成一页、页数与正文完全脱节
- 页码 = `scrollLeft / 栏宽`；栏宽 + 列距 = 视口宽 ⇒ `页数 = ceil(scrollWidth / 视口宽)`
- 多栏在布局阶段就不拆行盒 ⇒ 不会出现半行残字，也不会重复显示

安全：加载正文前用 `sanitizeForScript()` 去掉 EPUB 自带的 `<script>` 与内联事件属性，
脚本只能调用桥上的整数方法，拿不到文件/网络。

新增交互：6 种点击区域（三栏 / L 形 / Kindle 式 / 边缘 / 左右两栏 / 禁用）、
音量键翻页（`Activity.dispatchKeyEvent` 派发，避免 WebView 抢焦点后收不到）、
翻页动画（无 / 滑动 / 覆盖 / 淡入，用 WebView 快照实现）、字间距 / 词间距 / 两端对齐、
中键显隐上下栏。

实测（720x1600@280dpi，TXT 3000 字单段）：1/17 → 2/17 页首为完整行、页尾无半行；
17/17 是章末且无空白页；再往前翻进入下一章；章首往回回到上一章末页；音量键可翻页。

竖排仍只支持滚动模式：多栏的栏轴与横排相反，需要单独处理。

### 后续批次（计划）

- 批次二：批注 / 高亮 / 笔记、书签、全文搜索、目录增强（Room 迁移 + 选词交互）
- 批次三：简繁转换、引号替换、净化正则、页眉页脚、底部进度滑块、自动滚动、TTS
- 批次四：书架分组 / 列表视图、详情页章节状态、阅读器底部控制条（Mihon 化）
