package app.tsundoku.reader.ui.reader

import app.tsundoku.reader.data.prefs.ReaderTapZone

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
 * 脚本只改自己的 DOM 样式、只调用 `TsundokuBridge` 上那几个整数参数的方法，不碰文件与网络；
 * 加载前会把 EPUB 自带的 `<script>` 与内联事件属性清掉。
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
        tapZone: ReaderTapZone,
        vwDp: Int,
        vhDp: Int,
    ): String =
        TEMPLATE
            .replace("__PAGED__", if (paged) "true" else "false")
            .replace("__PAD__", padPx.coerceIn(0, 80).toString())
            .replace("__ZONE__", quote(tapZone.key))
            .replace("__VW__", vwDp.coerceAtLeast(80).toString())
            .replace("__VH__", vhDp.coerceAtLeast(80).toString())

    /** 运行时切换点击区域，不必重新加载正文。 */
    fun setZoneCall(tapZone: ReaderTapZone): String =
        "window.__tsu&&window.__tsu.setZone(" + quote(tapZone.key) + ")"

    private fun quote(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private val TEMPLATE = """
(function () {
  var B = window.TsundokuBridge || null;
  var PAGED = __PAGED__;
  var PAD = __PAD__;
  var ZONE = __ZONE__;
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

  function zone(x, y) {
    var w = window.innerWidth;
    var h = window.innerHeight;
    if (ZONE === 'disabled') return 0;
    var left = x;
    var right = w - x;
    if (ZONE === 'rightleft') return x < w / 2 ? -1 : 1;
    if (ZONE === 'edge') {
      if (left < w * 0.2) return -1;
      if (right < w * 0.2) return 1;
      return 0;
    }
    if (ZONE === 'kindle') {
      if (left < w * 0.25) return -1;
      if (right < w * 0.25) return 1;
      return 0;
    }
    if (ZONE === 'lshape') {
      if (y > h * 0.85) return x < w / 2 ? -1 : 1;
      if (left < w / 3) return -1;
      if (right < w / 3) return 1;
      return 0;
    }
    if (left < w / 3) return -1;
    if (right < w / 3) return 1;
    return 0;
  }

  function send(delta) {
    if (!B) return;
    try { B.tap(delta); } catch (e) {}
  }

  document.addEventListener('click', function (e) {
    var t = e.target;
    if (t && t.closest && t.closest('a')) return;
    if (window.getSelection && String(window.getSelection()).length > 0) return;
    var z = zone(e.clientX, e.clientY);
    if (z === 0) {
      if (B) { try { B.menu(); } catch (err) {} }
      return;
    }
    if (PAGED) send(z > 0 ? 1 : -1);
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

  window.addEventListener('scroll', report, { passive: true });
  window.addEventListener('resize', function () { measure(); report(); });
  window.addEventListener('load', function () { measure(); report(); });
  if (document.fonts && document.fonts.ready && document.fonts.ready.then) {
    document.fonts.ready.then(function () { measure(); report(); });
  }

  window.__tsu = {
    remount: function () { measure(); report(); },
    setZone: function (z) { ZONE = z; },
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
      scrollToPos((r || 0) * maxScroll);
      report();
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
