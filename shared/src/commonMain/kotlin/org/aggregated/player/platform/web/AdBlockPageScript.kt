package org.aggregated.player.platform.web

/**
 * 广告拦截的页面端脚本与桥接协议（设计文档 §4.3，**跨平台共用**）。
 *
 * 原本这段 JS 与桥注册名硬编码在 `AndroidTabWebViewController` 里；抽到 commonMain 后，
 * Android 与桌面（JCEF）控制器注入**同一份**脚本、复用**同一个**桥注册名 [ADBLOCK_JS_BRIDGE]，
 * 元素拾取 / SPA 重注入逻辑因此跨端一致，桌面端无需重写。
 *
 * 脚本约定（ES5，兼容老旧内核）：
 * 1. 定义 `window.__aggregatedApplyAdblock(css)`：写入固定 id 的 `<style>`（幂等）；
 * 2. 给 `history.pushState/replaceState` 打补丁并监听 `popstate/hashchange`，SPA 路由时
 *    回调 `aggregatedAdBlock.onRouteChanged()` 触发重注入；
 * 3. 元素拾取：标记模式开启时，**点击**（鼠标/触摸通用，走 `click` 事件）即拾取元素，
 *    结构化信息经 `aggregatedAdBlock.onElementPicked(json)` 回传——
 *    改用 click 而非长按：修掉「长按没弹框」（原实现只用 touch 事件 + 脆弱的 400ms/10px 计时），
 *    且桌面鼠标天然可用。被点元素会保持红色描边高亮（供二次确认期间辨认），
 *    由 `window.__aggregatedClearPickHighlight()` 在关闭标记模式时清除。
 * 4. C3 JS Hook 兜底（P2，**尽力而为**）：带独立幂等守卫地包装 `fetch` / `XHR.open`，
 *    只观察媒体特征 URL 并回传 `aggregatedAdBlock.onMediaFound(url)`，绝不改写或阻断原请求。
 *    bootstrap 每次 `applyElementHiding()` 都会重注，守卫是防止层层重复包装的关键；
 *    首屏早发的请求（注入发生在 `onPageFinished`）与 CSP 限制下的页面可能抓不到，属预期缺口。
 *
 * 用 [buildString] 而非长串 `+` 拼接：后者嵌套过深会触发 Kotlin 编译器递归类型检查崩溃。
 * **括号顺序极易拼错且 `evaluateJavascript` 不会回传 JS 语法错误**（会静默全失效），
 * 因此 [AdBlockPageScriptTest] 对生成结果做括号配对校验，改动本脚本务必跑它。
 */
object AdBlockPageScript {

    /** JS 桥接对象在页面里的注册名，须与脚本中调用的名字一致（桌面端注册同名桥即可复用）。 */
    const val ADBLOCK_JS_BRIDGE = "aggregatedAdBlock"

    /** 构建「注入元素隐藏样式」的完整脚本：引导脚本 + 写入 CSS 调用（空串表示清空）。 */
    fun buildApplyAdblockScript(css: String): String {
        // 转义单引号、反斜杠与换行，避免破坏 JS 字符串字面量
        val escaped = css.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "").replace("\n", "\\n")
        return "$ADBLOCK_BOOTSTRAP\n;window.__aggregatedApplyAdblock('$escaped');"
    }

    /** 一次性引导脚本（见文件头注释）。由各平台控制器在页面加载完成后注入一次。 */
    val ADBLOCK_BOOTSTRAP: String = buildString {
        append("(function(){")
        // === SPA 钩子 ===
        append("if(!window.__aggregatedAdBlockInstalled){window.__aggregatedAdBlockInstalled=true;")
        append("window.__aggregatedApplyAdblock=function(css){var id='__aggregated_adblock_style';")
        append("var s=document.getElementById(id);if(!s){s=document.createElement('style');s.id=id;")
        append("(document.head||document.documentElement).appendChild(s);}s.textContent=css||'';};")
        append("var _ps=history.pushState,_rs=history.replaceState;")
        append("function n(){try{aggregatedAdBlock.onRouteChanged();}catch(e){}}")
        append("history.pushState=function(){var r=_ps.apply(this,arguments);n();return r;};")
        append("history.replaceState=function(){var r=_rs.apply(this,arguments);n();return r;};")
        append("window.addEventListener('popstate',n);window.addEventListener('hashchange',n);}")
        // === 元素拾取侦测：仅标记模式开启时生效，点击即拾取（鼠标/触摸通用） ===
        append("window.__aggregatedMarkingEnabled=!!window.__aggregatedMarkingEnabled;")
        // 高亮清除入口定义在引导层（不在 click 回调内）：关闭标记模式时即便从未点过元素也能安全调用
        append("window.__aggregatedClearPickHighlight=function(){if(window.__aggregatedHighlighted){")
        append("try{window.__aggregatedHighlighted.el.style.outline=window.__aggregatedHighlighted.prev;}")
        append("catch(e){}window.__aggregatedHighlighted=null;}};")
        append("document.addEventListener('contextmenu',function(e){if(window.__aggregatedMarkingEnabled)")
        append("e.preventDefault();});")
        append("if(!window.__aggregatedPickInstalled){window.__aggregatedPickInstalled=true;")
        append("function stepInfo(n){var tag=n.tagName,id=n.id||null;")
        append("var cls=(n.className&&typeof n.className==='string')?n.className.split(/\\s+/).filter(Boolean):[];")
        append("var idx=0,tot=0;var p=n.parentElement;if(p){var ch=p.children;")
        append("for(var i=0;i<ch.length;i++){var c=ch[i];if(c.nodeType===1){tot++;if(c.tagName===tag)idx=tot;}}}")
        append("return{tag:tag,id:id,classes:cls,index:idx,siblingCount:tot};}")
        // 实时高亮预览框（滑到哪亮到哪）：固定层、pointer-events:none 不挡交互、随手指/鼠标移动重定位。
        // 移动端触摸被原生透明遮罩拦截，预览由原生 onTouchEvent 的 MOVE 驱动 showHover；
        // 桌面端无遮罩，由下方 mousemove 监听驱动；二者共用同一预览框，互不冲突。
        append("var __aggHoverBox=function(){var b=document.getElementById('__aggHover');")
        append("if(!b){b=document.createElement('div');b.id='__aggHover';")
        append("b.style.cssText='position:fixed;pointer-events:none;z-index:2147483647;")
        append("border:2px dashed #ff4d4f;background:rgba(255,77,79,0.15);display:none;box-sizing:border-box;';")
        append("document.body.appendChild(b);}return b;};")
        append("window.__aggregatedShowHover=function(x,y){if(!window.__aggregatedMarkingEnabled)return;")
        append("var el=document.elementFromPoint(x,y);")
        append("if(!el||el.nodeType!==1){window.__aggregatedHideHover();return;}")
        append("var r=el.getBoundingClientRect();var b=__aggHoverBox();")
        append("b.style.display='block';b.style.top=r.top+'px';b.style.left=r.left+'px';")
        append("b.style.width=r.width+'px';b.style.height=r.height+'px';};")
        append("window.__aggregatedHideHover=function(){var b=document.getElementById('__aggHover');")
        append("if(b)b.style.display='none';};")
        // 统一拾取：清掉预览框→清旧描边→持久描边→回溯 path→回传 onElementPicked；click 监听与原生遮罩命中共用。
        append("function pickEl(el){window.__aggregatedHideHover();if(!el||el.nodeType!==1)return;")
        append("window.__aggregatedClearPickHighlight();")
        append("var prev=el.style.outline;el.style.outline='2px solid #e53935';")
        append("window.__aggregatedHighlighted={el:el,prev:prev};")
        append("var r=el.getBoundingClientRect();")
        append("var path=[];var node=el;var g=0;")
        append("while(node&&node.nodeType===1&&g<25){path.push(stepInfo(node));node=node.parentElement;g++;}")
        append("var vw=window.innerWidth||document.documentElement.clientWidth;")
        append("var vh=window.innerHeight||document.documentElement.clientHeight;")
        append("var info={tag:el.tagName,id:el.id||null,")
        append("classes:(el.className&&typeof el.className==='string')?")
        append("el.className.split(/\\s+/).filter(Boolean):[],")
        append("path:path,width:Math.round(r.width),height:Math.round(r.height),")
        append("areaRatio:(r.width*r.height)/(vw*vh)};try{aggregatedAdBlock.onElementPicked(JSON.stringify(info));}catch(e){}}")
        // 兜底拾取（桌面端 / 未启用原生遮罩时）：捕获阶段拦截点击，e.target 即拾取元素。
        append("document.addEventListener('click',function(e){if(!window.__aggregatedMarkingEnabled)return;")
        append("if(!e.target||e.target.nodeType!==1)return;e.preventDefault();e.stopPropagation();")
        append("pickEl(e.target);},true);")
        // 桌面端鼠标移动预览（移动端触摸被原生遮罩拦截，改由原生 MOVE 驱动，不会冲突）
        append("document.addEventListener('mousemove',function(e){if(!window.__aggregatedMarkingEnabled)return;")
        append("window.__aggregatedShowHover(e.clientX,e.clientY);},false);")
        // 原生透明遮罩命中入口：坐标为 CSS 视口坐标（物理像素 / WebView scale），由 Android 层换算后传入；
        // 遮罩在 WebView 之上消费触摸，根本到不了页面，从而彻底拦住跳转 / 滚动，比依赖 preventDefault 可靠。
        append("window.__aggregatedHitTest=function(x,y){var el=document.elementFromPoint(x,y);pickEl(el);};")
        // 层级微调：基于当前高亮元素上/下钻一级重新拾取（移动端手指粗、容易点到子元素），
        // 重新 pickEl 会重描边并回传新 info，确认框据此刷新；当前无高亮时静默 no-op。
        append("window.__aggregatedPickParent=function(){var h=window.__aggregatedHighlighted;if(!h||!h.el||!h.el.parentElement)return;pickEl(h.el.parentElement);};")
        append("window.__aggregatedPickChild=function(){var h=window.__aggregatedHighlighted;if(!h||!h.el)return;var c=h.el.children&&h.el.children[0];pickEl(c||h.el);};}")
        // === C3 JS Hook 兜底（P2，尽力而为） ===
        // 独立守卫：bootstrap 每次 applyElementHiding() 都重注，无守卫会把 fetch/XHR 层层包装、回调成倍触发。
        append("if(!window.__aggregatedMediaHookInstalled){window.__aggregatedMediaHookInstalled=true;")
        // 关键词故意宽松：宿主侧 MediaCandidateFactory.sniff 还会用 MediaKindClassifier 二次判定丢弃噪声。
        append("var __aggLooksMedia=function(u){var s=String(u).toLowerCase();")
        append("return s.indexOf('m3u8')>=0||s.indexOf('.mpd')>=0||s.indexOf('.mp4')>=0||")
        append("s.indexOf('playlist')>=0||s.indexOf('manifest')>=0;};")
        // 相对路径借 <a> 解析成绝对 URL；blob: 不含关键词，不会被回传（它本来也不可外部播放）
        append("var __aggReportMedia=function(u){try{var a=document.createElement('a');a.href=String(u);")
        append("aggregatedAdBlock.onMediaFound(a.href);}catch(e){}};")
        append("if(window.fetch){var __aggFetch=window.fetch;window.fetch=function(input){")
        append("try{var u=(typeof input==='string')?input:(input&&input.url);")
        append("if(u&&__aggLooksMedia(u))__aggReportMedia(u);}catch(e){}")
        append("return __aggFetch.apply(this,arguments);};}")
        append("if(window.XMLHttpRequest&&window.XMLHttpRequest.prototype.open){")
        append("var __aggOpen=window.XMLHttpRequest.prototype.open;")
        append("window.XMLHttpRequest.prototype.open=function(method,url){")
        append("try{if(url&&__aggLooksMedia(url))__aggReportMedia(url);}catch(e){}")
        append("return __aggOpen.apply(this,arguments);};}}")
        // === 弹窗守卫（D1，对抗动态广告 / 反复显示 / 全屏遮罩弹窗） ===
        // 痛点：静态 <style> 选择器对「注入后才出现的弹窗」「广告 JS 反复把 display 改回」无能为力，
        // 表现为「拦截后还是可见」「弹窗类广告拦不住」。这里用 MutationObserver 持续对抗。
        // 阈值刻意保守（fixed 全屏遮罩、面积≥0.85、z≥9999），并豁免含 <video>/表单输入的元素，
        // 避免误杀登录框与站点视频播放器；误杀比漏杀更糟，宁可漏掉边界弹窗留待手动标记。
        append("if(!window.__aggregatedGuardInstalled){window.__aggregatedGuardInstalled=true;")
        append("function __aggHasVideo(n){if(!n||n.nodeType!==1)return false;if(n.tagName==='VIDEO')return true;")
        append("try{return !!n.querySelector('video');}catch(e){return false;}}")
        append("function __aggHasForm(n){if(!n||n.nodeType!==1)return false;")
        append("try{return !!n.querySelector('input,textarea,select');}catch(e){return false;}}")
        append("function __aggPv(n){try{var c=getComputedStyle(n);")
        append("if(c.position!=='fixed'&&c.position!=='absolute')return false;")
        append("if(c.display==='none'||c.visibility==='hidden')return false;")
        append("var z=parseInt(c.zIndex,10)||0;")
        append("var w=window.innerWidth||document.documentElement.clientWidth;")
        append("var h=window.innerHeight||document.documentElement.clientHeight;")
        append("var r=n.getBoundingClientRect();var a=(r.width*r.height)/(w*h);")
        append("if(a>=0.85&&z>=9999&&!__aggHasVideo(n)&&!__aggHasForm(n))return true;")
        append("return false;}catch(e){return false;}}")
        append("function __aggHp(n){try{n.style.setProperty('display','none','important');")
        append("n.setAttribute('data-agg-removed','1');}catch(e){}}")
        append("function __aggScan(n){if(!n||n.nodeType!==1)return;if(__aggPv(n)){__aggHp(n);return;}")
        append("var ch=n.children;if(ch){for(var i=0;i<ch.length;i++)__aggScan(ch[i]);}}")
        append("var __aggGe=false;window.__aggSetGuardEnabled=function(v){__aggGe=v;if(v)__aggScan(document.body);};")
        append("var __aggObs=new MutationObserver(function(ms){if(!__aggGe)return;")
        append("for(var i=0;i<ms.length;i++){var m=ms[i];")
        append("if(m.type==='childList'){for(var j=0;j<m.addedNodes.length;j++){var nd=m.addedNodes[j];")
        append("if(nd.nodeType===1)__aggScan(nd);}}")
        append("else if(m.type==='attributes'){var t=m.target;")
        append("if(t&&t.nodeType===1&&t.getAttribute('data-agg-removed')==='1'){")
        append("var cs;try{cs=getComputedStyle(t);}catch(e){cs=null;}")
        append("if(cs&&cs.display!=='none')__aggHp(t);}}}});")
        append("__aggObs.observe(document.documentElement,{childList:true,subtree:true,")
        append("attributes:true,attributeFilter:['style','class']});}")
        append("})();")
    }
}
