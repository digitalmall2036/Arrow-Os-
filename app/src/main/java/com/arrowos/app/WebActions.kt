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

    /** Taps the visible button/link whose label best matches one of [labels]. */
    fun tap(labels: List<String>, done: (Boolean) -> Unit) = run("tap(${JSONArray(labels)})", done)

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
  function label(e){
    var t=(e.getAttribute('aria-label')||e.getAttribute('title')||'').trim();
    if(!t) t=(e.innerText||e.value||'').trim();
    if(!t){ var img=e.querySelector&&e.querySelector('img[alt]'); if(img) t=img.alt; }
    return t.replace(/\s+/g,' ').toLowerCase();
  }
  function wordMatch(text,word){
    var w=word.replace(/[.*+?^${'$'}{}()|[\]\\]/g,'\\${'$'}&');
    return new RegExp('(^|[^a-z])'+w+'([^a-z]|${'$'})').test(text);
  }
  function clickable(){
    return [].slice.call(document.querySelectorAll(
      'a,button,[role=button],[role=link],[role=tab],[role=menuitem],[aria-label],[title],input[type=submit],input[type=button],summary,[onclick],[tabindex]'
    )).filter(visible);
  }
  function fire(e){
    try{ e.scrollIntoView({block:'center'}); }catch(x){}
    var r=e.getBoundingClientRect(), x=r.left+r.width/2, y=r.top+r.height/2;
    ['pointerdown','mousedown','pointerup','mouseup'].forEach(function(t){
      try{ e.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window})); }catch(x){}
    });
    e.click();
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
    tap:function(words){
      var best=null, bestScore=0;
      clickable().forEach(function(e){
        var t=label(e); if(!t||t.length>120) return;
        words.forEach(function(w){
          var s=0;
          if(t===w) s=100;
          else if(t.indexOf(w)===0&&wordMatch(t,w)) s=80;
          else if(wordMatch(t,w)) s=60;
          if(s>0){
            s-=Math.min(t.length,100)/10;
            if(e.tagName==='BUTTON'||e.getAttribute('role')==='button') s+=5;
            if(s>bestScore){ bestScore=s; best=e; }
          }
        });
      });
      return best?fire(best):false;
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
