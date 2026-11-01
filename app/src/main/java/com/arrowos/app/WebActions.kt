package com.arrowos.app

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Performs actions inside the page that is open in the WebView, by running small
 * JavaScript helpers: scrolling feeds, controlling the video, tapping buttons by
 * their label and typing into text boxes. This is what lets commands act *inside*
 * YouTube, TikTok, Facebook, etc.
 */
class WebActions(private val webView: WebView) {

    /** Scrolls the page (or the feed under the middle of the screen) by [screens] screen heights. */
    fun scroll(screens: Double, done: (Boolean) -> Unit = {}) = run("scroll($screens)", done)
    fun scrollToTop(done: (Boolean) -> Unit = {}) = run("edge(false)", done)
    fun scrollToBottom(done: (Boolean) -> Unit = {}) = run("edge(true)", done)

    /** Next/previous video in TikTok-style feeds: snap one item down or up. */
    fun nextItem(forward: Boolean, done: (Boolean) -> Unit = {}) = run("item(${if (forward) 1 else -1})", done)

    fun play(done: (Boolean) -> Unit = {}) = run("media('play')", done)
    fun pause(done: (Boolean) -> Unit = {}) = run("media('pause')", done)
    fun mute(done: (Boolean) -> Unit = {}) = run("media('mute')", done)
    fun unmute(done: (Boolean) -> Unit = {}) = run("media('unmute')", done)

    /**
     * Scans the page, finds the visible thing that best matches one of [labels]
     * (its text, accessibility label, tooltip, icon or internal name), outlines it
     * and taps it. [done] gets the name of what was tapped, or null if nothing fit.
     */
    fun tap(labels: List<String>, done: (String?) -> Unit) {
        webView.evaluateJavascript(
            "$HELPERS\n;(function(){try{return String(window.__arrow.tap(${JSONArray(labels)})||'')}catch(e){return ''}})()",
        ) { result ->
            val name = try { JSONArray("[$result]").optString(0) } catch (e: Exception) { "" }
            done(name.takeIf { it.isNotBlank() && it != "null" })
        }
    }

    /** Opens the [n]th video/result/post (1-based) visible on the page. */
    fun tapNth(n: Int, done: (Boolean) -> Unit) = run("nth($n)", done)

    /** Scrolls the [n]th video/result/post (1-based) into the middle of the screen. */
    fun scrollToNth(n: Int, done: (Boolean) -> Unit) = run("scrollToNth($n)", done)

    /** Types [text] into the focused text box, or the first visible one. */
    fun type(text: String, submit: Boolean, done: (Boolean) -> Unit) =
        run("type(${JSONObject.quote(text)}, $submit)", done)

    fun pressEnter(done: (Boolean) -> Unit = {}) = run("enter()", done)

    private fun run(call: String, done: (Boolean) -> Unit) {
        webView.evaluateJavascript("$HELPERS\n;(function(){try{return !!window.__arrow.$call}catch(e){return false}})()") { result ->
            done(result == "true")
        }
    }

    private companion object {
        // Kept dependency-free and defensive: sites change their markup often, so every
        // helper looks for things the way a person would (visible, labelled, large).
        val HELPERS = """
(function(){
  if (window.__arrow) return;
  function visible(e){
    if(!e||!e.getBoundingClientRect) return false;
    var r=e.getBoundingClientRect();
    if(r.width<2||r.height<2||r.bottom<0||r.right<0||r.top>innerHeight||r.left>innerWidth) return false;
    var s=getComputedStyle(e); return s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0';
  }
  function root(){ return document.scrollingElement||document.documentElement; }
  function scroller(){
    var points=[[0.5,0.5],[0.5,0.3],[0.5,0.7]];
    for(var i=0;i<points.length;i++){
      var el=document.elementFromPoint(innerWidth*points[i][0],innerHeight*points[i][1]);
      while(el&&el!==document.body&&el!==document.documentElement){
        var s=getComputedStyle(el);
        if(/(auto|scroll)/.test(s.overflowY)&&el.scrollHeight>el.clientHeight+20) return el;
        el=el.parentElement;
      }
    }
    return root();
  }
  function height(s){ return (s===root())?innerHeight:s.clientHeight; }
  function onScreen(e){
    var r=e.getBoundingClientRect(); return r.top>=0&&r.left>=0&&r.bottom<=innerHeight&&r.right<=innerWidth;
  }
  function fire(e){
    if(!onScreen(e)){ try{ e.scrollIntoView({block:'center'}); }catch(x){} }
    var r=e.getBoundingClientRect(), x=r.left+r.width/2, y=r.top+r.height/2;
    var opts={bubbles:true,cancelable:true,composed:true,clientX:x,clientY:y,view:window,button:0};
    var P=window.PointerEvent;
    ['pointerdown','mousedown','pointerup','mouseup'].forEach(function(t){
      try{
        var ev=(t.indexOf('pointer')===0&&P)?new P(t,Object.assign({pointerId:1,pointerType:'touch',isPrimary:true},opts)):new MouseEvent(t,opts);
        e.dispatchEvent(ev);
      }catch(x){}
    });
    if(typeof e.click==='function') e.click();
    else e.dispatchEvent(new MouseEvent('click',opts));
    if(e.focus&&/INPUT|TEXTAREA|SELECT/.test(e.tagName)) e.focus();
    return true;
  }
  function displayed(e){
    var r=e.getBoundingClientRect(); if(r.width<2||r.height<2) return false;
    var s=getComputedStyle(e); return s.visibility!=='hidden'&&s.display!=='none';
  }
  // Videos / posts / results on the page, top to bottom, including ones scrolled out of view.
  function items(){
    var seen={}, out=[];
    [].slice.call(document.querySelectorAll('a[href]')).forEach(function(a){
      if(!displayed(a)) return;
      var r=a.getBoundingClientRect();
      var media=a.querySelector('img,video,picture,[style*=background-image]');
      if(!media&&r.width*r.height<8000) return;
      if(r.height<40) return;
      var href=a.href.split('#')[0]; if(seen[href]) return; seen[href]=1;
      out.push({a:a,top:r.top+(window.scrollY||0),left:r.left});
    });
    if(!out.length){
      [].slice.call(document.querySelectorAll('video')).filter(displayed).forEach(function(v){
        var r=v.getBoundingClientRect(); out.push({a:v,top:r.top+(window.scrollY||0),left:r.left});
      });
    }
    out.sort(function(x,y){ return Math.abs(x.top-y.top)<20?x.left-y.left:x.top-y.top; });
    return out.map(function(o){ return o.a; });
  }

  // ---------- eyes ----------
  var CLICKABLE='a,button,input,select,textarea,summary,label,[role],[onclick],[tabindex],[aria-label],[title],[data-e2e],[data-testid],[jsaction],ytm-button-renderer,yt-icon-button,tp-yt-paper-icon-button';
  function allElements(rootNode,out){
    var list=rootNode.querySelectorAll(CLICKABLE+',svg,img,span,div');
    for(var i=0;i<list.length;i++){
      var e=list[i]; out.push(e);
      if(e.shadowRoot) allElements(e.shadowRoot,out);
    }
    return out;
  }
  function isClickable(e){
    if(!e||e.nodeType!==1) return false;
    var t=e.tagName;
    if(t==='A'||t==='BUTTON'||t==='SUMMARY'||t==='SELECT'||(t==='INPUT'&&e.type!=='hidden')) return true;
    var role=e.getAttribute('role');
    if(role&&/button|link|tab|menuitem|option|checkbox|switch|radio/.test(role)) return true;
    if(e.hasAttribute('onclick')||e.hasAttribute('jsaction')) return true;
    if(e.tabIndex>=0&&e.hasAttribute('tabindex')) return true;
    try{ if(getComputedStyle(e).cursor==='pointer') return true; }catch(x){}
    return false;
  }
  function clickTarget(e){
    // An icon or text inside a button: tap the button itself.
    var n=e;
    for(var i=0;i<6&&n&&n!==document.body;i++){
      if(isClickable(n)) return n;
      n=n.parentElement||(n.getRootNode&&n.getRootNode().host);
    }
    return null;
  }
  function words(str){
    return (str||'').replace(/([a-z])([A-Z])/g,'${'$'}1 ${'$'}2').replace(/[_\-\.\/:]+/g,' ')
      .toLowerCase().replace(/\s+/g,' ').trim();
  }
  // Everything that tells us what an element is, strongest evidence first.
  function names(e){
    var out=[];
    function add(v,w){ v=words(v); if(v&&v.length<=140) out.push({t:v,w:w}); }
    add(e.getAttribute('aria-label'),1);
    var lb=e.getAttribute('aria-labelledby');
    if(lb){ lb.split(' ').forEach(function(id){ var x=document.getElementById(id); if(x) add(x.textContent,1); }); }
    add(e.getAttribute('title'),1);
    add(e.getAttribute('alt'),0.95);
    add(e.getAttribute('placeholder'),0.9);
    if(e.tagName==='INPUT') add(e.value,0.9);
    var txt=(e.innerText||e.textContent||'').trim();
    if(txt&&txt.length<=80) add(txt,1);
    var svgTitle=e.querySelector&&e.querySelector('svg title'); if(svgTitle) add(svgTitle.textContent,0.9);
    var img=e.querySelector&&e.querySelector('img[alt]'); if(img) add(img.getAttribute('alt'),0.85);
    var lbl=e.querySelector&&e.querySelector('[aria-label]'); if(lbl&&lbl!==e) add(lbl.getAttribute('aria-label'),0.85);
    ['data-e2e','data-testid','data-tooltip','data-title','name','id'].forEach(function(a){ add(e.getAttribute(a),0.7); });
    if(typeof e.className==='string') add(e.className,0.5);
    var icon=e.querySelector&&e.querySelector('[class*=icon],[icon],yt-icon,svg[class]');
    if(icon){ add(icon.getAttribute('icon'),0.6); if(typeof icon.className==='string') add(icon.className,0.45);
      else if(icon.className&&icon.className.baseVal) add(icon.className.baseVal,0.45); }
    return out;
  }
  function close(a,b){
    // Allows one wrong letter for longer words, so "subscibe" still finds "subscribe".
    if(a===b) return true; if(a.length<5||Math.abs(a.length-b.length)>1) return false;
    var i=0,j=0,edits=0;
    while(i<a.length&&j<b.length){
      if(a[i]===b[j]){i++;j++;continue;}
      if(++edits>1) return false;
      if(a.length>b.length) i++; else if(b.length>a.length) j++; else {i++;j++;}
    }
    return edits+(a.length-i)+(b.length-j)<=1;
  }
  function phraseScore(name,want){
    if(name===want) return 100;
    var nw=name.split(' '), ww=want.split(' ');
    var padded=' '+name+' ';
    if(padded.indexOf(' '+want+' ')>=0) return name.indexOf(want)===0?85:75;
    var found=0;
    ww.forEach(function(w){ if(nw.some(function(n){ return n===w||close(n,w); })) found++; });
    if(found===ww.length) return 60;
    if(!/[a-z0-9]/.test(want)&&name.indexOf(want)>=0) return 70; // arrow symbols like "›"
    return 0;
  }
  function see(want){
    want=want.map(words).filter(function(w){ return w; });
    var best=null, screen=innerWidth*innerHeight;
    allElements(document,[]).forEach(function(e){
      if(!displayed(e)) return;
      var ns=names(e); if(!ns.length) return;
      var score=0, name='';
      ns.forEach(function(n){
        want.forEach(function(w,wi){
          var s=phraseScore(n.t,w)*n.w;
          if(wi>0) s*=0.92; // the user's own word beats a synonym
          s-=Math.min(n.t.length,120)/12;
          if(s>score){ score=s; name=n.t; }
        });
      });
      if(score<=25) return;
      // Tap the button that owns this text or icon. Sites built with React often
      // attach clicks invisibly, so a labelled element with no clickable parent is
      // still tapped itself, just ranked a little lower.
      var target=clickTarget(e);
      if(target===e) score+=6;
      else if(!target){ target=e; score-=8; }
      var r=target.getBoundingClientRect();
      if(r.width*r.height>screen*0.5) return;       // whole-page wrappers aren't buttons
      if(onScreen(target)) score+=10;                // prefer what the user can see
      else if(!visible(target)) score-=6;            // off screen: scrolled to before tapping
      if(!best||score>=best.score) best={el:target,score:score,name:name}; // ties: the innermost match wins
    });
    return best;
  }
  // Briefly outline what the eyes chose, so the user sees what is being tapped.
  function show(el){
    try{
      var r=el.getBoundingClientRect(), box=document.createElement('div');
      box.style.cssText='position:fixed;z-index:2147483647;pointer-events:none;border:3px solid #00D1FF;'+
        'border-radius:10px;box-shadow:0 0 18px #00D1FF;transition:opacity .4s;'+
        'left:'+(r.left-4)+'px;top:'+(r.top-4)+'px;width:'+(r.width+8)+'px;height:'+(r.height+8)+'px';
      document.documentElement.appendChild(box);
      setTimeout(function(){ box.style.opacity='0'; },700);
      setTimeout(function(){ box.remove(); },1200);
    }catch(x){}
  }
  function bestVideo(){
    var vs=[].slice.call(document.querySelectorAll('video'));
    var shown=vs.filter(visible);
    shown.sort(function(a,b){
      var ra=a.getBoundingClientRect(), rb=b.getBoundingClientRect();
      return rb.width*rb.height-ra.width*ra.height;
    });
    return shown[0]||vs[0]||null;
  }
  window.__arrow={
    scroll:function(f){ var s=scroller(); s.scrollBy({top:height(s)*f,behavior:'smooth'}); return true; },
    edge:function(bottom){ var s=scroller(); s.scrollTo({top:bottom?s.scrollHeight:0,behavior:'smooth'}); return true; },
    item:function(dir){
      var s=scroller(), before=s.scrollTop;
      s.scrollBy({top:height(s)*dir,behavior:'smooth'});
      // Some feeds don't scroll natively and only react to arrow keys; try that if nothing moved.
      setTimeout(function(){
        if(Math.abs(s.scrollTop-before)>5) return;
        var key=dir>0?'ArrowDown':'ArrowUp';
        try{ document.dispatchEvent(new KeyboardEvent('keydown',{key:key,code:key,keyCode:dir>0?40:38,bubbles:true})); }catch(x){}
      },450);
      return true;
    },
    media:function(what){
      var v=bestVideo();
      if(!v||!visible(v)){
        // "play it" after "scroll to the second video": open that item.
        var last=window.__arrow.last;
        if(last&&last.tagName==='VIDEO') v=last;
        else if(what==='play'&&last&&document.contains(last)) return fire(last);
        if(!v) return false;
      }
      if(what==='play'){ var p=v.play(); if(p&&p.catch) p.catch(function(){}); }
      else if(what==='pause') v.pause();
      else if(what==='mute') v.muted=true;
      else if(what==='unmute'){ v.muted=false; if(v.volume===0) v.volume=1; }
      return true;
    },
    // The "eyes": scan everything tappable on the page, work out what each thing is,
    // pick the best match for the words, show it, and tap it. Returns what was tapped.
    tap:function(words){
      var hit=see(words); if(!hit) return '';
      if(!onScreen(hit.el)){ try{ hit.el.scrollIntoView({block:'center'}); }catch(x){} }
      show(hit.el); fire(hit.el);
      return hit.name||words[0];
    },
    nth:function(n){
      var hit=items()[n-1]; if(!hit) return false;
      window.__arrow.last=hit; return fire(hit);
    },
    scrollToNth:function(n){
      var hit=items()[n-1];
      if(!hit){ return window.__arrow.item(1); } // feeds like TikTok: just move on to the next one
      window.__arrow.last=hit;
      try{ hit.scrollIntoView({block:'center',behavior:'smooth'}); }catch(x){ hit.scrollIntoView(); }
      return true;
    },
    type:function(text,submit){
      var i=document.activeElement;
      var ok=i&&(i.tagName==='INPUT'||i.tagName==='TEXTAREA'||i.isContentEditable);
      if(!ok){
        i=[].slice.call(document.querySelectorAll(
          'input[type=search],input[type=text],input:not([type]),textarea,[contenteditable=true],[contenteditable=""]'
        )).filter(visible)[0];
      }
      if(!i) return false;
      i.focus();
      if(i.isContentEditable){ document.execCommand('insertText',false,text); }
      else{
        var proto=i.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(proto,'value').set.call(i,text);
        i.dispatchEvent(new Event('input',{bubbles:true}));
        i.dispatchEvent(new Event('change',{bubbles:true}));
      }
      if(submit) window.__arrow.enter();
      return true;
    },
    enter:function(){
      var i=document.activeElement||document.body;
      ['keydown','keypress','keyup'].forEach(function(t){
        i.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
      });
      var f=i.form||(i.closest&&i.closest('form'));
      if(f){ if(f.requestSubmit) f.requestSubmit(); else f.submit(); }
      return true;
    }
  };
})();
"""
    }
}
