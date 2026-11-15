# 工程状态与交接笔记

> 生成于 2026-11-14，记录当前环境、已完成项与待办，方便换工作区/换机器后继续。

## 工程

- 路径：`D:\CRPEER\编程\Tsundoku`
- 包名：`app.tsundoku.reader`
- 技术栈：Kotlin 2.0.21 + Jetpack Compose(Material3) + Room + OkHttp + Jsoup
- 构建：AGP 8.7.3 / Gradle 8.14.3 / compileSdk 36 / minSdk 26
- Git：`main` 分支，已完成 2 次提交

## 已实现功能

- 书架：封面网格、阅读进度、搜索过滤、长按菜单、删除
- 发现：来源列表 + 全局搜索入口
- 全局搜索：多来源并发、逐源进度、失败状态、「仅显示有结果」
- 来源：网页源（声明式 JSON 规则）/ OPDS / RSS / 本地导入
- 阅读器：TXT·EPUB 用 WebView 渲染（注入主题 CSS），PDF 用 PdfRenderer 横滑翻页
- 导入：TXT（UTF-8/GBK 识别 + 章节正则切分）、EPUB（解包保真）、PDF（首页封面）
- 设置：字号 13–34px、四套阅读主题（纸质/白纸/护眼/夜间）

## 开发环境（重要）

| 项 | 路径 |
| --- | --- |
| Android Studio | `D:\CRPEER\Android\studio`（2025.1.1 Narwhal，便携版） |
| SDK | `E:\code\Android\sdk`（已从 D 盘迁移） |
| AVD | `E:\code\Android\avd`（`tsundoku` = pixel_6 / API 36 google_apis x86_64 / 3 GB RAM） |
| JDK | `D:\CRPEER\Android\jdk` → `D:\CRPEER\toolchain\jdk21\jdk-21.0.2` |
| Gradle 缓存 | `D:\CRPEER\Android\gradle-home` |
| 环境脚本 | `D:\CRPEER\Android\env.bat`（cmd）、`setup-env.ps1`（PS，需 ExecutionPolicy） |
| 启动 Studio | `D:\CRPEER\Android\start-studio.bat` |

联接（Junction）：

- `D:\CRPEER\Android\sdk` → `E:\code\Android\sdk`
- `D:\CRPEER\toolchain\android-sdk` → `E:\code\Android\sdk`
- `D:\CRPEER\Android\avd` → `E:\code\Android\avd`

## 构建命令

```powershell
$env:JAVA_HOME="D:\CRPEER\Android\jdk"
$env:ANDROID_HOME="E:\code\Android\sdk"
$env:GRADLE_USER_HOME="D:\CRPEER\Android\gradle-home"
cd D:\CRPEER\编程\Tsundoku
.\gradlew.bat :app:assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

安装：`adb install -r app\build\outputs\apk\debug\app-debug.apk`

## 跑模拟器（2026-11-14 已跑通）

```powershell
# 一条命令启动（窗口模式，已处理 AVD 路径 / TEMP / USER_HOME）
D:\CRPEER\编程\Tsundoku\tools\start-emulator.bat

# 之后
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n app.tsundoku.reader/.MainActivity
```

冷启动约 40–120 秒（`sys.boot_completed=1` 为准）。

关键点：

- AVD 在 `E:\code\Android\avd`，**必须先设 `ANDROID_AVD_HOME`**，否则 `emulator -list-avds` 为空、
  `avdmanager` 会去 `%USERPROFILE%\.android\avd` 找。
- 用 **`-gpu guest`（客户机内软件渲染）**。`-gpu host` / `swiftshader_indirect` / `off` 在本机都会卡死：
  日志停在 `emuglConfig_init` 之后，qemu 只有约 1 CPU 秒、adb 无设备。
  `-gpu guest` 只是慢一点，能稳定启动和渲染。
- 附加 `-skin 720x1600`（默认 1080x2400 软件渲染太慢）、`-no-audio -no-metrics -no-snapshot -no-boot-anim`。
- `TMP`/`TEMP` 指到 `E:\code\Android\tmp`，`ANDROID_USER_HOME` 指到
  `E:\code\Android\user-home`，避免写爆 C 盘。
- 旧脚本用的是 `-no-window -gpu off`，既看不到界面又容易卡住，已废弃。
- **`-skin 720x1600` 必须配 `adb shell wm density 280`**（已设过，存在 AVD 数据里）。
  只改分辨率不改密度，屏幕只有约 274dp 宽，会把 UI 挤到"看起来坏了"：
  详情页「加入书架并阅读」变成圆形按钮且文字竖排、阅读设置第 4 个主题被裁掉。
  720×1600 + 280dpi ≈ 411dp，与 pixel_6 原生一致。
  想临时加速可以 `wm density 420` 换回 274dp，但别拿那个宽度判断布局对错。

## 第三批：Legado 书源 + 追更页（2026-11-15 凌晨）

- **新增来源类型 `SourceType.LEGADO("阅读书源")`**，实现见 `data/source/legado/`：
  - `LegadoRule` 规则翻译（`class./id./tag./CSS` + 索引 + `@text/@href/@html/属性` + `||` + `##正则`
    + `{{key}}/{{page}}/{{page-1}}/{{baseUrl}}`）
  - `LegadoSource`（search / details / chapters / content，带 Referer + Accept-Language）
  - `LegadoImporter`（导入 JSON 数组，原始 JSON 原样存档）
  - 不支持 `@js:` / `<js>` / `@get:` / `&&`，遇到会提示换源
- **导入界面**：更多 → 导入阅读书源 → 选 JSON → 按分组筛选 → 勾选 → 导入。
  2640 个源解析约 10 秒，列表用 `LazyColumn`，不会一次全选（避免 2640 个源同时搜索）。
- **追更页（新 Tab「更新」）**：`UpdatesRepository.scan()` 遍历书架里的远端书，拉目录和本地章节对比，
  只追加新章节（不动已读进度），点条目直接跳到第一个新章节。
- **调试入口**：`am start -n app.tsundoku.reader/.MainActivity --es q 关键字`
  （模拟器 `input text` 不支持中文，只能这样注入查询）。
- 实测与限制见 `docs/legado-sources.md`：120 个小说源里只有 2 个能出结果。

### 踩坑记录

- **工程路径含中文会让 Gradle 单测报 `ClassNotFoundException`**（worker 类路径编码问题）。
  用 `mklink /J D:\tsundoku_build "D:\CRPEER\编程\Tsundoku"` 映射后单测正常。
- Legado `bookSourceUrl` 可能带 `#标签`，直接当 Referer 会让 OkHttp 抛
  `Unexpected char ... in Referer value`，必须先剥掉。
- `URI("https://host").resolve("relative")` 会拼成 `https://hostrelative`，base 无路径时要补 `/`。
- 规则里残留的 `{{...}}` 是非法 URI 字符，会让 `URI.resolve` 抛异常并导致 "no scheme" 报错。
- `@text` 之外的属性（如 `title`、`alt`）不能走 `absUrl()`，否则标题会变成拼出来的 URL。

## 第四批：书籍目录 / 封面 / 翻页（2026-11-15 凌晨）

### Mihon 式目录结构（已实测）

```
files/books/<书名>/              ← 一本书一个文件夹
  book.epub | book.txt | book.pdf
  pages/…                        EPUB 解包章节
  cover.png                      封面（导入时自动生成，可手动替换）
files/meta/<id>.json             或写到上面目录里的 meta.json（设置可切）
```

实测（模拟器 `adb shell run-as app.tsundoku.reader`）：

- `files/books/149228/{book.epub, cover.png(646KB), pages/}`
- `files/meta/6.json`：
  ```json
  {"id":6,"title":"149228 utf-8","format":"EPUB",
   "progress":{"chapterIndex":0,"chapterTitle":"1","totalChapters":98,"readChapters":0},
   "annotations":[]}
  ```

### 封面

- 书架长按 → **更换封面** → 系统文件选择器 → 任意图片（jpg/png/jfif 都行）。
  实现：`BookStorage.writeCover()` 解码后缩到最长边 1200px，统一写成 `cover.png`。
- 删除书籍时会询问「保留阅读进度与批注（存成 meta.json）」，勾选则先落盘再删库记录；
  书籍文件与封面始终保留在 `books/`。

### 阅读模式：滚动 / 翻页

- `ReaderSettings.layout`：`SCROLL`（整章纵向滚动）/ `PAGED`（左右翻页）。
- 翻页实现：CSS 多列（`column-width = 视口宽 - 2*边距`，`column-gap = 2*边距`，所以一页步长正好等于视口宽）
  + `html { overflow-x: auto; overflow-y: hidden }`，横向拖动时 `web.scrollBy(±web.width)`。
- 顶栏显示 `1 / 98 · 第 2 页`；竖排模式下强制走滚动（两套横向轴会打架）。

### 踩坑

- `WebView.computeHorizontalScrollRange()` 是 protected，取不到总页数，只能显示「第 N 页」。
- 模拟器长按要用 `input swipe x y x y 2000`（900ms 会被当成点击）。
- `ActivityResultContracts.GetContent()` 在 API 33+ 会走相册选择器，看不到 Downloads 里的图；
  换成 `OpenDocument()` 才能从文件管理器选。
- 该 EPUB 的 `dc:title` 就是 `149228 utf-8`（文件名叫 `149228+utf-8`），所以书名"正确"但难看；
  章节标题也只能取到 `1`（toc.ncx 的 label），后续可从 `chapterN.xhtml` 的 `<title>` 兜底。

## 第五批：审查问题修复（2026-11-15）

按「先核对、再写测试复现、最后改」的顺序处理了审查里的 P0 与阅读器/书源问题，
逐条对照见 **`docs/review-fixes.md`**（含每项的文件、原因、验证方式与未做项）。

要点：

- `RuleParser`：文本不再被当成 URL（原来 `anonymous` 会变 `https://site/anonymous`）；
  `select()` 也支持 `||`；支持 `div.a@a@href` 链式
- `EpubImporter`：`Path` 规范化判越界（原来字符串前缀判断可被同前缀兄弟目录绕过）、
  条目/体积上限、失败清理
- 数据库：去掉 `fallbackToDestructiveMigration`，加 `MIGRATION_1_2`（`books.lastPositionOffset`）
  与升级前自动备份；模拟器实测 v1→v2 数据无损，备份文件生成
- 目录刷新：改为按 URL 合并（保留缓存正文/已读/进度），事务内完成
- 正文分页：`ContentPaginator` 跟随 `nextContentUrl` 合并整章，带去重/页数上限/同主机限制
- 阅读进度：记录章节内位置、退出前落盘、**打开章节不再直接算已读**
- 空章节/失败：`ChapterContent.Failure` + 重试按钮 + 8s 超时兜底
- 分类浏览：`browse(url, page)` 不再忽略 url；Legado `exploreUrl` 解析成分类 chip
- 书源诊断：搜索/详情/目录/正文逐段测试，给出状态、耗时、具体错误

单测从 0 增到 47 条（`app/src/test/`）。

## 主链路实测（2026-11-14，全部通过）

在模拟器上走完并逐屏截图（`docs/screenshot-*.png`）：

1. 发现 → 来源列表（RSS 示例订阅）→ 全局搜索「AI」→ `已完成 1/1`、1 条结果
2. 点结果 → 详情页（在线 · 3 章、简介、目录）→「加入书架并阅读」
3. 阅读器：WebView 渲染在线章节，正文 + 图片 + 章节切换 `1/3`、`上/下一章` 正常
4. 返回书架：书籍入架、显示 `3 章`、进度条 `1/3`（读完自动记进度）
5. 书架「+」→ 系统文件选择器 → `Download/示例书籍.txt` → 导入成功（`2 章`）
6. 打开 TXT：章节切分正确（第一章 纸页与灯），排版正常
7. 阅读设置：字号滑杆 19px、四套主题；切「夜间」内容区变深色
8. 更多页：导入本地书籍 / 添加来源（网页源·OPDS·RSS 三个 tab、JSON 模板）/ 管理来源 / 阅读设置

观察到的两个小问题（非阻塞，未改代码）：

- 切到「夜间」后，阅读器顶部栏（返回/目录/设置）仍是浅色 app 主题，内容区已是深色，观感割裂。
- 更多页最下方「关于」只有分组标题，下面没有条目。

## 第二批：跟随系统 + 排版（2026-11-14 晚）

- **主题跟随系统**：`TsundokuTheme` 在 API 31+ 用 `dynamicLight/DarkColorScheme`（Material You，
  取系统壁纸色），低版本回落到 Material 3 基线配色；深/浅色跟随 `isSystemInDarkTheme()`。
  旧的棕色调色板已删除。
- **阅读器顶栏/底栏配色跟随阅读主题**（修掉上面第一条小问题）：`Scaffold.containerColor`、
  `TopAppBarDefaults.topAppBarColors`、底栏 `Surface` 都用当前阅读主题的 bg/fg。
- **排版设置**（`ReaderSettings` + DataStore 持久化）：
  字号 13–34、行距 1.2–2.6、段距 0–2em、首行缩进 0–3 字、页边距 12–40px、
  字族（默认/宋体/黑体/楷体/等宽）、字重（常规/中等/加粗）、
  书写方向（横排/竖排）、主题（跟随系统/纸质/白纸/护眼/夜间）。
  阅读器内齿轮打开的面板是全部设置；更多页只保留字号 + 主题速选（并显示排版摘要）。
- **竖排实现要点**：`writing-mode: vertical-rl` 时块轴是水平的，行长度 = 块的 block-size。
  WebView 不认 `100vh`/`100%`，所以用 `BoxWithConstraints` 量出高度（dp 数值即 CSS px，
  因为 viewport meta 是 `width=device-width, initial-scale=1`）后写进
  `html/body { height: NNpx }`；否则每列只剩一个字。竖排是横向滚动，扫动方向与横排相反。
- 顺带把 `loadWithOverviewMode` 关掉、给本地导入的 HTML 也补了 viewport meta。

## 书源合集 JSON 分析（Read最新2000+书源合集(3).json）

- 共 **2640** 个源，是「阅读/Legado 3.0」的 `bookSource` 格式，字段：
  `bookSourceName/Url/Group/Type`、`searchUrl`、`ruleSearch`、`ruleBookInfo`、`ruleToc`、
  `ruleContent`、`ruleExplore`、`ruleReview`，以及 `header/loginUrl/variableComment`
  等（部分源需要登录、Cookie 或 JS）。类型分布：小说 2518 / 漫画 61 / 听书 39 /
  音乐 13 / 影视 9。
- **和本项目现有 `WebSourceConfig` 不兼容**：现在是纯 CSS 选择器
  （`item: ".book-item"`、`title: "h3 a@text"`），Legado 是自有 DSL：
  `class.foo@tag.a@text`、`id.bar@text`、`tag.dl`、索引 `@a.1` / `@td.-1`、
  `@text|@href|@html`、`{{key}}`/`{{page}}`、`||` 兜底、`##` 正则、`@js:` 脚本。
- 要落地需要一层 **Legado 兼容层**（规则翻译 + 导入选择器 + 失败切换），
  建议顺序：`class./id./tag.` + 索引 + `@text/@href/@html` + `{{key}}/{{page}}` + `||` + `##`
  → 先支持纯 HTML 站点，`@js:` 与登录类源后续再做。

## 已知问题

1. **C 盘空间紧张**（100 GB 盘，2026-11-14 清理后约 2.2 GB 可用），太小会让终端、Studio、emulator
   写临时文件失败。占空间大头：`C:\Users\44412\AppData\Roaming`（24.6 GB：.minecraft 8.1 /
   Tencent 4.9 / kingsoft 4.2）、`C:\Windows` 27 GB、`Program Files*` 28 GB。需要管理员或手工清理，
   且 **C 盘没有 pagefile/hiberfil**。
2. **内存吃紧**：物理 10.2 GB，常用只剩 ~1 GB；提交上限 20.8 GB（分页文件在 `D:\pagefile.sys`）
   已用约 18 GB。模拟器启动前建议关掉多余的 VS Code / Chrome 窗口。
3. **同时装了 4 套杀软**（360安全卫士、腾讯电脑管家、McAfee、Windows Defender），互相 hook，
   也是模拟器/构建卡顿的常见原因，建议只留一套。
4. 网页源规则依赖目标站点结构，需按站点自行编写。

## 待办（下一步）

- [x] 模拟器跑通并装上 APK（`docs/screenshot-shelf.png` 为书架实拍）
- [x] 走一遍主链路：发现 → 全局搜索 → 详情 → 加入书架 → 阅读 → 导入 TXT → 阅读 → 阅读设置（见上节）
- [x] 主题跟随系统（Material You）+ 阅读器 chrome 跟随阅读主题
- [x] 排版：字族 / 字重 / 字号 / 行距 / 段距 / 缩进 / 页边距 / 横排·竖排
- [x] Legado 书源导入（2640 个源）+ 规则兼容层 + 导入界面
- [x] 追更页「更新」：扫描书架、追加新章节
- [x] Mihon 式一书一目录 + cover.png + meta.json（进度/批注），可选存放位置
- [x] 删除书籍时询问是否保留进度/批注
- [x] 阅读模式：滚动 / 左右翻页
- [ ] 批注（划线 + 笔记，Room 存储 + 阅读器内列表）—— 未开始，meta.json 的 annotations 目前恒为空
- [ ] EPUB 章节标题/书名的兜底策略（当前取 ncx label 与 dc:title）
- [ ] 书源：补 `@js:` / `<js>` / `@get:` 变量，提高可用率（当前 120 个里只有 2 个能出结果）
- [ ] 书源：探索页 `exploreUrl`（发现页直接浏览排行榜）
- [ ] 更多页「关于」分组补内容（版本号 / 开源许可 / 链接）
- [ ] 书库分类 / 标签、排序
- [ ] 章节离线下载与批量缓存
- [ ] 阅读器：翻页动画、行距/边距、TTS
- [ ] 从文件管理器「用积读打开」（manifest 已声明意图，逻辑待接入）
- [ ] GitHub 建仓并 push（本地已就绪，`local.properties` 已被忽略）
