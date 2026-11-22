package app.tsundoku.reader.ui.reader

/**
 * 阅读器内嵌脚本：**CSS 多栏分页**（与 Readest / foliate-js 同一思路）。
 *
 * 两个坑都是实测出来的，所以这里的设计是「所有尺寸都在 DOM 坐标系里，且用一个自己注入的
 * 滚动容器」：
 * 1. `WebView.contentHeight` 是 CSS 像素、View 的 `scrollTo` 是物理像素（实测 720px 宽的
 *    WebView：`clientWidth` 1216、实际可滚动量 2297 物理像素）。跨坐标系换算必然让页码与正文错位。
 *    这里栏宽 = 视口宽、步长 = 栏宽、页码 = `scrollLeft / 栏宽`，全部在 CSS 像素里。
 * 2. 多栏必须设在**根元素**上，并且根元素自己当滚动容器（`overflow: hidden` + 程序化 `scrollLeft`）。
 *    设在 body 或普通 div 上时列溢出会被裁掉（实测 `scrollWidth` 恒等于视口宽），见 foliate-js 的
 *    `paginator.columnize()`。
 *
 * 用多栏而不是「按视口高度滚动」，是因为多栏在布局阶段就不会把行盒切到两栏里，
 * 天然没有半行残字。
 *
 * 脚本只改自己的 DOM 样式、只通过 `TsundokuBridge` 上报状态（页码、选区、点击点），
 * 不碰文件与网络；加载前会把 EPUB 自带的 `<script>` 与内联事件属性清掉。
 *
 * 点击区域与高亮命中的**判定**不在这里：脚本把 (坐标, 文本节点路径, 字符偏移) 交给 Kotlin，
 * 由 `ReaderTapZoneMath` / `AnnotationHitTest` 决定行为，那两处可以写真正的单元测试。
 */
internal object ReaderScript {

    /**
     * 生成注入到正文里的脚本。
     *
     * [vwDp] / [vhDp] 是 Kotlin 侧量到的视口尺寸（dp = CSS 像素）。**不能**用脚本里的
     * `window.innerWidth` / `documentElement.clientWidth`：实测同一台机器不同章节会给出
     * 412 / 1628 两种值（宽视口与布局视口不一致），分页结果随之完全错误。
     */
    fun build(
        paged: Boolean,
        padPx: Int,
        vwDp: Int,
        vhDp: Int,
        density: Float,
    ): String =
        TEMPLATE
            .replace("__PAGED__", if (paged) "true" else "false")
            .replace("__PAD__", padPx.coerceIn(0, 80).toString())
            .replace("__VW__", vwDp.coerceAtLeast(80).toString())
            .replace("__VH__", vhDp.coerceAtLeast(80).toString())
            .replace("__DPR__", String.format(java.util.Locale.US, "%.3f", density))

    private val TEMPLATE = """
(function () {
  var B = window.TsundokuBridge || null;
  var PAGED = __PAGED__;
  var PAD = __PAD__;
  if (window.__tsu) { try { window.__tsu.remount(); } catch (e) {} return; }
  if (window.__tsuLoaded) return;
  window.__tsuLoaded = true;

  var step = 0;
  var pages = 1;
  var maxScroll = 0;
  var ready = false;
  var lastVw = -1;
  var lastVh = -1;

  var CFG_VW = __VW__;
  var CFG_VH = __VH__;
  var DPR = __DPR__;
  var annotations = [];

  /** 视口尺寸固定用 Kotlin 侧量到的值，见 build() 的注释。 */
  function vw() { return CFG_VW; }

  function vh() { return CFG_VH; }

  function scroller() {
    return PAGED ? document.documentElement : (document.scrollingElement || document.documentElement);
  }

  function pos() {
    if (PAGED) return Math.abs(document.documentElement.scrollLeft);
    return window.scrollY;
  }

  function scrollToPos(p) {
    var v = Math.max(0, Math.min(p, maxScroll));
    if (PAGED) document.documentElement.scrollLeft = v;
    else window.scrollTo(0, v);
  }

  /**
   * 翻页模式：把**根元素**变成多栏容器（参考 foliate-js 的 paginator.columnize）。
   * 关键点：多栏必须设在 documentElement 上——根元素的列溢出会成为它自己的可滚动区域，
   * 设在 body 或普通 div 上只会被裁掉（实测 scrollWidth 恒等于视口宽度）。
   */
  function layout() {
    var W = vw();
    var H = vh();
    var html = document.documentElement;
    var body = document.body;
    if (!PAGED) {
      step = 0;
      return;
    }
    // 用真实盒宽（可能与配置值差 1px），保证「列宽 + 列距」正好等于步长
    var boxWidth = html.clientWidth || W;
    var colWidth = Math.max(80, boxWidth - 2 * PAD);
    var gap = 2 * PAD;
    html.style.boxSizing = 'border-box';
    html.style.height = H + 'px';
    html.style.padding = '0 ' + PAD + 'px';
    html.style.margin = '0';
    html.style.position = 'static';
    html.style.columnWidth = colWidth + 'px';
    html.style.columnGap = gap + 'px';
    html.style.columnFill = 'auto';
    html.style.overflow = 'hidden';
    html.style.maxWidth = 'none';
    html.style.maxHeight = 'none';
    if (body) {
      body.style.maxWidth = 'none';
      body.style.maxHeight = 'none';
      body.style.margin = '0';
    }
    step = boxWidth;
  }

  function measure() {
    var W = vw();
    var H = vh();
    lastVw = W;
    lastVh = H;
    // 脚本可能先于 WebView 拿到尺寸（此时是 0），这种测量作废
    if (W <= 0 || H <= 0) { ready = false; return; }
    layout();
    var el = scroller();
    if (PAGED) {
      maxScroll = Math.max(0, el.scrollWidth - (el.clientWidth || W));
      pages = Math.max(1, Math.ceil((el.scrollWidth - 1) / Math.max(1, step)));
    } else {
      maxScroll = Math.max(0, el.scrollHeight - H);
      pages = 1;
    }
    ready = true;
  }
  /** 视口变了或上次测量失败就重新测；所有对外接口都先走这里。 */
  function ensure() {
    if (!ready || lastVw !== vw() || lastVh !== vh()) measure();
  }
  function pageOf(p) {
    if (!PAGED || step <= 0) return 0;
    var i = Math.round(p / step);
    return Math.max(0, Math.min(i, pages - 1));
  }

  // ---------- 批注：选区、序列化、渲染、命中 ----------

  /** 文本节点 → 相对 body 的路径（第 n 个子节点的 n/n/n 形式）。 */
  function pathOf(node) {
    var parts = [];
    var n = node;
    while (n && n.parentNode && n !== document.body) {
      var idx = 0;
      var sib = n;
      while ((sib = sib.previousSibling)) idx++;
      parts.unshift(idx);
      n = n.parentNode;
    }
    return parts.join('/');
  }

  function nodeAt(path) {
    var n = document.body;
    if (!path) return n;
    var parts = String(path).split('/');
    for (var i = 0; i < parts.length; i++) {
      var idx = parseInt(parts[i], 10);
      if (isNaN(idx) || !n || !n.childNodes || !n.childNodes[idx]) return null;
      n = n.childNodes[idx];
    }
    return n;
  }

  function rangeOf(a) {
    var sn = nodeAt(a.sp);
    var en = nodeAt(a.ep);
    if (!sn || !en) return null;
    try {
      var r = document.createRange();
      r.setStart(sn, a.so);
      r.setEnd(en, a.eo);
      return r;
    } catch (e) {
      return null;
    }
  }

  /** 用 CSS Custom Highlight API 渲染高亮：不改 DOM，所以路径永远有效。 */
  function applyHighlights() {
    if (!window.CSS || !CSS.highlights || typeof Highlight === 'undefined') return false;
    for (var c = 0; c < 4; c++) {
      try { CSS.highlights.delete('tsu-hl-' + c); } catch (e) {}
    }
    var groups = [[], [], [], []];
    for (var i = 0; i < annotations.length; i++) {
      var r = rangeOf(annotations[i]);
      if (r) groups[Math.abs(annotations[i].color | 0) % 4].push(r);
    }
    for (var k = 0; k < 4; k++) {
      if (!groups[k].length) continue;
      try { CSS.highlights.set('tsu-hl-' + k, new Highlight(...groups[k])); } catch (e) {}
    }
    return true;
  }

  function reportSelection() {
    if (!B) return;
    var sel = window.getSelection();
    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) {
      try { B.selectionCleared(); } catch (e) {}
      return;
    }
    var range = sel.getRangeAt(0);
    var text = String(sel);
    if (!text || !text.trim()) {
      try { B.selectionCleared(); } catch (e) {}
      return;
    }
    var rect = range.getBoundingClientRect();
    var payload = {
      text: text.length > 2000 ? text.slice(0, 2000) : text,
      sp: pathOf(range.startContainer),
      so: range.startOffset,
      ep: pathOf(range.endContainer),
      eo: range.endOffset,
      x: (rect.left + rect.width / 2) * DPR,
      y: rect.top * DPR,
      h: rect.height * DPR
    };
    try { B.selection(JSON.stringify(payload)); } catch (e) {}
  }

  /** 全书搜索跳转：找到文字、选中它，并把视口滚过去。 */
  function findAndScroll(text) {
    if (!text) return 'false';
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var node = null;
    var found = null;
    while ((node = walker.nextNode())) {
      var idx = node.nodeValue ? node.nodeValue.indexOf(text) : -1;
      if (idx >= 0) { found = { n: node, i: idx }; break; }
    }
    if (!found) return 'false';
    var r = document.createRange();
    r.setStart(found.n, found.i);
    r.setEnd(found.n, found.i + text.length);
    var sel = window.getSelection();
    if (sel) { sel.removeAllRanges(); sel.addRange(r); }
    var rect = r.getBoundingClientRect();
    if (PAGED) {
      var target = Math.max(0, pos() + rect.left - 20);
      scrollToPos(Math.floor(target / Math.max(1, step)) * step);
    } else {
      window.scrollTo(0, Math.max(0, window.scrollY + rect.top - 80));
    }
    report();
    return 'true';
  }
  function state() {
    ensure();
    var p = Math.max(0, Math.min(pos(), maxScroll));
    return {
      page: pageOf(p),
      pages: pages,
      ratio: maxScroll > 0 ? p / maxScroll : 0,
      atEnd: p >= maxScroll - 2
    };
  }

  var pending = false;
  function report() {
    if (!B || pending) return;
    pending = true;
    window.requestAnimationFrame(function () {
      pending = false;
      var s = state();
      try { B.state(s.page, s.pages, s.ratio, s.atEnd ? 1 : 0); } catch (e) {}
    });
  }

  /** 左右滑动翻页（点区域判定在 Kotlin 侧，滑动方向是明确的，不需要判定）。 */
  function send(delta) {
    if (!B) return;
    try { B.tap(delta); } catch (e) {}
  }

  /**
   * 点击正文：把「点击坐标 + 落点所在的文本节点与字符偏移」交给 Kotlin。
   *
   * 命中高亮与点击区域都在 Kotlin 侧判定（`AnnotationHitTest` / `ReaderTapZoneMath`），
   * 这样判定逻辑可以被单元测试覆盖；脚本只负责测量与上报。
   * 注意不能先算区域再上报，否则「点击区域被禁用」就会退回成「中间区域」。
   */
  document.addEventListener('click', function (e) {
    var t = e.target;
    if (t && t.closest && t.closest('a')) return;
    if (window.getSelection && String(window.getSelection()).length > 0) return;
    if (!B || !B.point) return;
    var caret = document.caretRangeFromPoint
      ? document.caretRangeFromPoint(e.clientX, e.clientY)
      : null;
    var node = caret && caret.startContainer ? caret.startContainer : null;
    var path = node ? pathOf(node) : '';
    var offset = caret ? caret.startOffset : -1;
    try {
      B.point(e.clientX, e.clientY, path, offset);
    } catch (err) {}
  }, true);

  var sx = 0, sy = 0, st = 0, tracking = false;
  document.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1) { tracking = false; return; }
    tracking = true;
    sx = e.touches[0].clientX;
    sy = e.touches[0].clientY;
    st = Date.now();
  }, { passive: true });

  document.addEventListener('touchend', function (e) {
    if (!tracking) return;
    tracking = false;
    if (!PAGED) return;
    var t = e.changedTouches[0];
    if (!t) return;
    var dx = t.clientX - sx;
    var dy = t.clientY - sy;
    if (Date.now() - st > 700) return;
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5) send(dx < 0 ? 1 : -1);
  }, { passive: true });

  document.addEventListener('touchmove', function (e) {
    if (PAGED && e.touches.length === 1 && e.cancelable) e.preventDefault();
  }, { passive: false });

  var selTimer = 0;
  function scheduleSelection() {
    if (selTimer) window.clearTimeout(selTimer);
    selTimer = window.setTimeout(reportSelection, 160);
  }
  document.addEventListener('selectionchange', scheduleSelection);
  document.addEventListener('touchend', function () {
    window.setTimeout(reportSelection, 80);
  }, { passive: true });

  window.addEventListener('scroll', report, { passive: true });
  window.addEventListener('resize', function () { measure(); report(); });
  window.addEventListener('load', function () { measure(); report(); });
  if (document.fonts && document.fonts.ready && document.fonts.ready.then) {
    document.fonts.ready.then(function () { measure(); report(); });
  }

  window.__tsu = {
    remount: function () { measure(); report(); },
    measure: measure,
    ensure: ensure,
    turn: function (d) {
      ensure();
      var s = state();
      var t = s.page + d;
      if (t < 0 || t >= pages) return 'false';
      scrollToPos(t * step);
      report();
      return 'true';
    },
    page: function (i) {
      ensure();
      var t = Math.max(0, Math.min(i | 0, pages - 1));
      scrollToPos(t * step);
      report();
      return 'true';
    },
    ratio: function (r) {
      ensure();
      var target = (r || 0) * maxScroll;
      // 翻页模式必须落在页边界上：重排后按比例算出的偏移通常在两列之间，
      // 直接滚过去会同时露出上一列的后半行和下一列的前半行（半列残字）。
      if (PAGED && step > 0) {
        target = Math.round(target / step) * step;
      }
      scrollToPos(target);
      report();
      return 'true';
    },
    highlights: function (json) {
      try { annotations = JSON.parse(json || '[]') || []; } catch (e) { annotations = []; }
      return applyHighlights() ? 'true' : 'false';
    },
    find: function (text) { return findAndScroll(text); },
    clearSelection: function () {
      var sel = window.getSelection();
      if (sel) sel.removeAllRanges();
      reportSelection();
      return 'true';
    },
    info: function () {
      ensure();
      var s = state();
      return s.page + ',' + s.pages;
    }
  };

  measure();
  if (ready) {
    report();
    if (B) { try { B.ready(); } catch (e) {} }
  }
})();
""".trimIndent()
}
