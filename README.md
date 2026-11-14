# 积读 Tsundoku

一个 **Mihon 风格** 的 Android 电子书阅读器。

书库首页以封面网格铺开，像 Mihon 的「书架」一样一眼看全；同时借鉴 [Readest](https://github.com/readest/readest) 的阅读体验，支持多来源聚合搜索与本地电子书导入。

> 名称「积读（つんどく）」取自日语「積読」——买书如山倒，读书如抽丝。

---

## 功能

### 书库（Mihon 式首页）
- 封面网格，自适应列数
- 阅读进度条 + 已读章节数
- 按书名 / 作者本地过滤
- 长按弹出操作菜单（详情 / 移除）

### 多来源
| 类型 | 说明 |
| --- | --- |
| 网页源 | 声明式 JSON 规则，用 CSS 选择器描述搜索、目录、正文，无需写代码 |
| OPDS | 标准书库目录协议，可下钻多级目录，书籍自动下载导入 |
| RSS / Atom | 订阅源当作一本连载书，每篇文章一章 |
| 本地文件 | EPUB / TXT / PDF |

### 全局搜索
- 在所有已启用来源中 **并发** 搜索
- 每个来源独立展示结果，带「已完成 x/y」进度与失败状态
- 「仅显示有结果」过滤
- 点击结果直接进入详情并加入书架

### 阅读
- TXT：自动识别 UTF-8 / GBK，按「第X章」「Chapter N」自动切分章节
- EPUB：整本解包到应用私有目录，章节以本地 XHTML 文件加载，图片 / CSS / 脚注保持完整
- PDF：基于系统 `PdfRenderer` 渲染，横向翻页
- 阅读主题：纸质 / 白纸 / 护眼 / 夜间
- 正文字号 13–34px 可调
- 目录跳转、上一章 / 下一章、阅读进度自动保存

---

## 技术栈

- **Kotlin 2.0.21** + **Jetpack Compose**（Material 3）
- **Room** 持久化（书籍 / 章节 / 来源）
- **DataStore** 保存阅读设置
- **OkHttp** + **Jsoup** 抓取与解析
- **kotlinx.serialization** 描述来源规则
- 手写依赖容器 `AppContainer`，不依赖 Hilt / Koin，编译快、易读
- 无任何原生库 / NDK 依赖

```
app/src/main/java/app/tsundoku/reader/
├── data/
│   ├── import/      TXT / EPUB / PDF 导入器
│   ├── local/       Room 实体、DAO、数据库
│   ├── net/         HTTP 客户端（编码识别、下载）
│   ├── prefs/       阅读设置
│   ├── repo/        书库仓库
│   └── source/      来源引擎（web / opds / rss）
├── domain/model/    领域模型与来源规则定义
├── di/              AppContainer
└── ui/              Compose 页面（书架 / 发现 / 搜索 / 详情 / 阅读器 / 设置）
```

---

## 构建

环境要求：

- JDK 17+（推荐 21）
- Android SDK Platform 36、Build-Tools 36
- Gradle 由 wrapper 提供（8.14.3）

```bash
# Debug APK
./gradlew :app:assembleDebug

# 产物
app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 打开根目录同步构建。

> 如果项目路径包含中文（例如 `D:\CRPEER\编程\Tsundoku`），`gradle.properties` 中已设置
> `android.overridePathCheck=true`，否则 AGP 会拒绝构建。

---

## 编写网页源

添加来源 → 选择「网页源」，粘贴 JSON：

```json
{
  "name": "示例站点",
  "base_url": "https://example.com",
  "lang": "zh",
  "search": {
    "url": "/search?q={{query}}&page={{page}}",
    "item": ".book-item",
    "title": "h3 a@text",
    "link": "h3 a@href",
    "cover": "img@src",
    "author": ".author@text"
  },
  "chapters": {
    "item": "#list dd a",
    "title": "@text",
    "link": "@href",
    "reverse": true
  },
  "content": {
    "selector": "#content",
    "remove": ["script", "style", ".ads"]
  }
}
```

规则语法 `css选择器@属性`：

| 写法 | 含义 |
| --- | --- |
| `h3 a@text` | 取文本 |
| `h3 a@href` | 取链接并补全为绝对地址 |
| `img@data-src` | 取任意属性 |
| `@text` | 元素自身文本 |
| `a@text\|\|.title@text` | 候选选择器，取第一个命中的 |

占位符：`{{query}}`（已 URL 编码）、`{{page}}`。

---

## 路线图

- [ ] 书库分类 / 标签与排序
- [ ] 书籍详情页「换源」
- [ ] 章节批量下载与离线阅读
- [ ] 阅读器：翻页动画、行距 / 边距设置、TTS
- [ ] OPDS 搜索（OpenSearch）自动发现
- [ ] 从文件管理器直接「用积读打开」（intent 已声明，逻辑待接入）
- [ ] 导入词库式 JS 源（类 Mihon 扩展，需沙箱）

---

## 免责声明

本项目不内置、不分发任何书籍内容。来源需由使用者自行添加，请确保你添加的来源与内容符合当地法律法规及站点服务条款。

---

## License

[MIT](LICENSE)
