/*
 * Arrow Cloner's screens. The brain and planner are in logic.js; the phone side
 * (recording, files, web reading, running jobs inside Arrow Os) is window.ArrowOs.
 */
(function () {
  'use strict';
  var L = window.ClonerLogic;
  var $ = function (id) { return document.getElementById(id); };

  // ---------- Talking to Arrow Os ----------

  // In a normal browser (for previews) there is no phone: a small stand-in keeps the page usable.
  if (!window.ArrowOs) {
    window.ArrowOs = {
      loadBrain: function () { try { return localStorage.getItem('brain') || ''; } catch (e) { return ''; } },
      saveBrain: function (j) { try { localStorage.setItem('brain', j); } catch (e) { } },
      account: function () { return JSON.stringify({ uid: 'preview', email: 'preview@arrowos' }); },
      fetch: function (url, id) { setTimeout(function () { Cloner.onCallback(id, JSON.stringify({ ok: false, status: 0, body: '', error: 'preview' })); }, 300); },
      pickFiles: function (id) { setTimeout(function () { Cloner.onCallback(id, JSON.stringify({ files: [] })); }, 100); },
      pickFolder: function (id) { setTimeout(function () { Cloner.onCallback(id, JSON.stringify({ files: [] })); }, 100); },
      startRecording: function () { toast('Recording works on the phone.'); },
      open: function (url) { window.open(url, '_blank'); },
      runJob: function () { toast('Jobs run on the phone.'); },
      jobContinue: function () { }, stopJob: function () { },
      searchTools: function () { return '[]'; }, suggestTools: function () { return '[]'; }, customTool: function () { return ''; },
      homeTools: function () { return '[]'; }, addTools: function () { }, removeTool: function () { },
      speak: function () { }, listen: function (id) { Cloner.onCallback(id, JSON.stringify({ text: '' })); },
      goHome: function () { }, toast: function (t) { toast(t); }, showFiles: function () { },
      listenToPage: function () { toast('Listening works on the phone.'); }, pickAndListen: function () { toast('Listening works on the phone.'); },
      listenToFile: function () { }, stopListening: function () { }, saveLearned: function () { },
      phoneFiles: function (q, id) { setTimeout(function () { Cloner.onCallback(id, JSON.stringify({ files: [] })); }, 50); },
    };
  }
  var OS = window.ArrowOs;
  var pending = {};
  var seq = 0;

  function nativeAsync(method) {
    var args = Array.prototype.slice.call(arguments, 1);
    return new Promise(function (resolve) {
      var id = 'c' + (++seq);
      pending[id] = resolve;
      OS[method].apply(OS, args.concat([id]));
    });
  }

  function fetchPage(url) { return nativeAsync('fetch', url); }

  // ---------- State ----------

  var brain = L.loadBrain(OS.loadBrain());
  var currentPlan = null;
  var jobState = null; // {plan, index, running}
  var screen = 'start';
  var tab = 'work';
  var researchFromHub = false;
  var inspiration = null;

  function save() { OS.saveBrain(JSON.stringify(brain)); }

  /** Learns [item] and keeps a copy as a text file in Arrow Files ("Arrow Cloner - Learned"). */
  function keep(item) {
    var k = L.learn(brain, item);
    OS.saveLearned(k.title, k.text);
    return k;
  }

  function show(name) {
    ['start', 'research', 'hub'].forEach(function (s) { $(s).hidden = s !== name; });
    screen = name;
    window.scrollTo(0, 0);
  }

  function showTab(name) {
    tab = name;
    ['work', 'teach', 'tools', 'brain'].forEach(function (t) { $('tab-' + t).hidden = t !== name; });
    document.querySelectorAll('.tabbar button').forEach(function (b) { b.classList.toggle('on', b.dataset.tab === name); });
    if (name === 'tools') renderTools();
    if (name === 'brain') renderBrain();
    if (name === 'teach') renderLessons();
    if (name === 'work') renderHistory();
    renderSetup();
    window.scrollTo(0, 0);
  }

  var toastTimer = null;
  function toast(text) {
    var t = $('toast');
    t.textContent = text;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.hidden = true; }, 3200);
  }

  function el(tag, attrs, children) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'text') e.textContent = attrs[k];
      else if (k === 'class') e.className = attrs[k];
      else if (k.indexOf('on') === 0) e.addEventListener(k.slice(2), attrs[k]);
      else e.setAttribute(k, attrs[k]);
    });
    (children || []).forEach(function (c) { if (c) e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return e;
  }

  function fill(list, items) {
    list.innerHTML = '';
    items.forEach(function (i) { list.appendChild(i); });
  }

  function ago(ms) {
    if (!ms) return '';
    var s = Math.round((Date.now() - ms) / 1000);
    if (s < 60) return 'just now';
    if (s < 3600) return Math.round(s / 60) + ' min ago';
    if (s < 86400) return Math.round(s / 3600) + ' h ago';
    return Math.round(s / 86400) + ' days ago';
  }

  // ---------- Voice ----------

  document.querySelectorAll('[data-mic]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var target = $(btn.dataset.mic);
      btn.classList.add('listening');
      nativeAsync('listen').then(function (r) {
        btn.classList.remove('listening');
        if (r.error) { toast(r.error); return; }
        if (!r.text) return;
        target.value = target.tagName === 'TEXTAREA' && target.value.trim() ? target.value.trim() + ' ' + r.text : r.text;
        target.focus();
      });
    });
  });

  // ---------- 1. Profession ----------

  var EXAMPLES = ['Website developer', 'Graphic designer', 'Social media manager', 'Photographer', 'Accountant', 'Online seller', 'Teacher', 'Video editor'];
  fill($('professionChips'), EXAMPLES.map(function (e) {
    return el('button', { class: 'chip', text: e, onclick: function () { $('professionInput').value = e; startResearch(); } });
  }));
  $('professionGo').addEventListener('click', startResearch);
  $('professionInput').addEventListener('keydown', function (e) { if (e.key === 'Enter') startResearch(); });

  function startResearch() {
    var name = $('professionInput').value.trim();
    if (!name) { toast('Type or say a profession first.'); return; }
    researchFromHub = false;
    research(name);
  }

  // ---------- 2. Research on Arrow Os Google ----------

  async function wikipedia(query) {
    var r = await fetchPage('https://en.wikipedia.org/w/api.php?action=opensearch&limit=1&namespace=0&format=json&search=' + encodeURIComponent(query));
    try {
      var title = JSON.parse(r.body)[1][0];
      if (!title) return null;
      var s = await fetchPage('https://en.wikipedia.org/api/rest_v1/page/summary/' + encodeURIComponent(title.replace(/ /g, '_')));
      var j = JSON.parse(s.body);
      if (!j.extract || j.type === 'disambiguation') return null;
      return { title: j.title, extract: j.extract, url: (j.content_urls && j.content_urls.mobile && j.content_urls.mobile.page) || ('https://en.m.wikipedia.org/wiki/' + encodeURIComponent(title)) };
    } catch (e) { return null; }
  }

  async function webSearch(query) {
    var r = await fetchPage('https://html.duckduckgo.com/html/?q=' + encodeURIComponent(query));
    return r.ok ? L.parseDuckDuckGo(r.body) : [];
  }

  async function research(name) {
    show('research');
    $('researchTitle').textContent = L.titleCase(name);
    $('researchBusy').hidden = false;
    $('researchBody').hidden = true;
    $('researchBusyText').textContent = 'Searching Arrow Os Google for “' + name + '”…';
    var profile = L.professionProfile(name);
    var wiki = await wikipedia(name);
    $('researchBusyText').textContent = 'Reading what the work needs…';
    var results = await webSearch(name + ' job description duties');
    var reqs = await webSearch(name + ' requirements qualifications skills');
    profile = L.addResearch(profile, { wikipedia: wiki, results: results, requirements: reqs });

    brain.worker.profession = profile.name;
    if (!brain.worker.createdAt) brain.worker.createdAt = Date.now();
    brain.profile = profile;
    var text = [profile.summary, 'What the work is: ' + profile.duties.join('; ') + '.', 'Skills: ' + profile.skills.join('; ') + '.']
      .concat((profile.requirements || []).map(function (r) { return 'Requirement: ' + r; }))
      .concat(results.concat(reqs).map(function (r) { return r.title + ': ' + r.snippet; })).join('\n');
    keep({ kind: 'web', title: 'About being a ' + profile.name.toLowerCase(), text: text, source: 'Arrow Os Google' });
    save();
    renderProfile();
    $('researchBusy').hidden = true;
    $('researchBody').hidden = false;
    if (!wiki && !results.length) toast('I couldn\'t reach the web, so this is what I already knew. I\'ll read more when you\'re online.');
  }

  function renderProfile() {
    var p = brain.profile;
    if (!p) return;
    $('researchTitle').textContent = p.name;
    $('profileSummary').textContent = p.summary;
    fill($('profileDuties'), p.duties.map(function (d) { return el('li', { text: d }); }));
    fill($('profileSkills'), p.skills.map(function (d) { return el('li', { text: d }); }));
    $('profileReqWrap').hidden = !(p.requirements && p.requirements.length);
    fill($('profileReqs'), (p.requirements || []).map(function (d) { return el('li', { text: d }); }));
    fill($('profileAccounts'), p.accounts.map(function (a) {
      return el('li', {}, [
        el('span', { class: 'tag ' + a.kind, text: a.kind === 'payment' ? 'Pay' : 'Login' }),
        el('div', { class: 'need-text' }, [el('div', { class: 'need-label', text: a.label }), el('div', { class: 'need-why', text: a.why })]),
      ]);
    }));
    $('profileSourcesCard').hidden = !(p.sources && p.sources.length);
    fill($('profileSources'), (p.sources || []).map(function (s) {
      return el('li', {}, [
        el('a', { href: s.url, text: s.title, onclick: function (e) { e.preventDefault(); OS.open(s.url, s.title); } }),
        s.snippet ? el('div', { class: 'snip', text: s.snippet }) : null,
      ]);
    }));
    $('researchNext').textContent = researchFromHub ? 'Done' : 'Teach your digital worker';
  }

  $('seeOnGoogle').addEventListener('click', function () {
    var p = brain.profile;
    OS.open('https://www.google.com/search?q=' + encodeURIComponent((p ? p.name : '') + ' job requirements'), 'Google');
  });
  $('researchNext').addEventListener('click', function () {
    show('hub');
    renderHub();
    showTab(researchFromHub ? 'brain' : 'teach');
  });

  // ---------- 3. The hub ----------

  document.querySelectorAll('.tabbar button').forEach(function (b) {
    b.addEventListener('click', function () { showTab(b.dataset.tab); });
  });
  $('goHome').addEventListener('click', function () { OS.goHome(); });

  function renderHub() {
    $('hubRole').textContent = brain.worker.profession || 'Digital worker';
  }

  /** The first-time steps: profession → teach → tools → first job. */
  function renderSetup() {
    var taught = brain.knowledge.length > 2 || brain.lessons.length > 0;
    var tooled = JSON.parse(OS.homeTools() || '[]').length > 0;
    var worked = brain.jobs.length > 0;
    var strip = $('setupStrip');
    strip.hidden = worked;
    var states = { teach: taught, tools: tooled, work: worked };
    strip.querySelectorAll('[data-step]').forEach(function (s) {
      var k = s.dataset.step;
      s.classList.toggle('done', states[k]);
      s.classList.toggle('now', k === tab && !states[k]);
    });
    $('teachNext').hidden = worked;
    $('toolsNext').hidden = worked;
  }
  $('teachNext').addEventListener('click', function () { showTab('tools'); });
  $('toolsNext').addEventListener('click', function () { showTab('work'); });

  // ---------- Teach: record a lesson ----------

  $('lessonStart').addEventListener('click', function () {
    var title = $('lessonTitle').value.trim();
    if (!title) { toast('Say what you are going to show me first.'); $('lessonTitle').focus(); return; }
    OS.startRecording(title, $('lessonVoice').checked);
  });

  function onLesson(data) {
    if (!data.ok) { toast(data.error || 'Nothing was recorded.'); return; }
    var steps = L.lessonSteps(data.events || []);
    var lesson = { id: 'lesson-' + Date.now().toString(36), title: data.title, steps: steps, video: data.video || null, createdAt: Date.now() };
    brain.lessons.push(lesson);
    save();
    $('lessonTitle').value = '';
    show('hub');
    showTab('teach');
    var msg = 'I learned “' + data.title + '”' + (steps.length ? ' with ' + steps.length + ' steps.' : '.');
    if (!steps.length) msg += ' I saw no Arrow Os steps, so I kept the video for you.';
    toast(msg);
    OS.speak('Thank you. I learned ' + data.title);
  }

  function renderLessons() {
    $('lessonsWrap').hidden = !brain.lessons.length;
    fill($('lessonList'), brain.lessons.slice().reverse().map(lessonItem));
  }

  function lessonItem(l) {
    var li = el('li', {}, [
      el('div', { class: 'item-head' }, [
        el('span', { class: 'kind lesson', text: 'Lesson' }),
        el('span', { class: 'item-title', text: l.title }),
        el('button', { class: 'del', text: 'Forget', onclick: function (e) { e.stopPropagation(); forgetItem(l.id, l.title); } }),
      ]),
      el('div', { class: 'item-meta', text: l.steps.length + ' steps' + (l.video ? ' · video in Arrow Files' : '') + ' · ' + ago(l.createdAt) }),
      el('div', { class: 'item-preview', text: l.steps.map(function (s, i) { return (i + 1) + '. ' + L.describeStep(s); }).join('\n') || 'No steps' }),
    ]);
    li.addEventListener('click', function () { li.classList.toggle('open'); });
    return li;
  }

  // ---------- Teach: files and folders ----------

  $('uploadFiles').addEventListener('click', function () { upload('pickFiles'); });
  $('uploadFolder').addEventListener('click', function () { upload('pickFolder'); });

  function upload(method) {
    $('uploadStatus').textContent = 'Waiting for you to pick…';
    nativeAsync(method).then(function (r) {
      var files = r.files || [];
      if (r.error) { $('uploadStatus').textContent = r.error; return; }
      if (!files.length) { $('uploadStatus').textContent = ''; return; }
      var read = 0, kept = 0, skipped = 0;
      files.forEach(function (f) {
        if (f.skipped) { skipped++; return; }
        kept++;
        var title = (f.folder ? f.folder + '/' : '') + f.name;
        if (f.readable && f.text) {
          read++;
          L.learn(brain, { kind: 'file', title: title, text: f.text, source: 'upload', fileId: f.fileId });
        } else {
          L.learn(brain, { kind: 'file', title: title, text: '(' + (f.kind || 'file') + ' kept in Arrow Files for jobs)', source: 'upload', fileId: f.fileId });
        }
      });
      save();
      var parts = ['Learned from ' + read + ' file' + (read === 1 ? '' : 's')];
      if (kept - read) parts.push((kept - read) + ' kept in Arrow Files');
      if (skipped) parts.push(skipped + ' skipped (too big or unreadable)');
      $('uploadStatus').textContent = parts.join(' · ') + '.';
      renderSetup();
    });
  }

  // ---------- Teach: learn from YouTube, social media or the web ----------

  $('learnGo').addEventListener('click', function () {
    var t = $('learnInput').value.trim();
    if (!t) { toast('Tell me what to learn.'); return; }
    learnFrom(t, $('learnLog')).then(function () { renderSetup(); });
  });
  // Opens the search in Arrow Os and listens: tap a video, it plays out loud and I write down what is said.
  $('learnListen').addEventListener('click', function () {
    var t = $('learnInput').value.trim();
    if (!t) { toast('Tell me what to listen to.'); return; }
    var l = L.parseLearn(t);
    OS.listenToPage(L.sourceSearchUrl(l.source === 'web' ? 'youtube' : l.source, l.query), l.topic || l.query);
  });

  $('listenPick').addEventListener('click', function () {
    OS.pickAndListen($('listenTitle').value.trim());
  });

  function onHeard(data) {
    show('hub');
    showTab('teach');
    if (!data.text) { toast('I didn\'t hear any words. Turn the volume up and try again.'); return; }
    L.learn(brain, { kind: 'heard', title: 'Heard - ' + data.title, text: data.text, source: 'listened on the phone', fileId: data.fileId || null });
    save();
    $('listenTitle').value = '';
    var words = data.text.split(/\s+/).length;
    toast('I heard ' + words + ' words about “' + data.title + '” and saved them.');
    OS.speak('I listened and learned about ' + data.title);
    renderSetup();
  }

  function logLine(list, text, cls) {
    if (!list) return;
    list.appendChild(el('li', { text: text, class: cls || '' }));
  }

  /** Reads about [order] on YouTube and the web, and keeps what it read in the brain. */
  async function learnFrom(order, log) {
    var l = L.parseLearn(order);
    if (log) log.innerHTML = '';
    var learned = 0;
    if (!l.query) { logLine(log, 'I didn\'t understand what to learn.', 'bad'); return 0; }

    // The phone first: files already in Arrow Files about this.
    var onPhone = (await nativeAsync('phoneFiles', l.query + ' ' + l.topic)).files || [];
    onPhone.forEach(function (f) {
      if (f.text) {
        L.learn(brain, { kind: 'phone', title: f.name, text: f.text, source: 'on your phone (' + f.source + ')', fileId: f.fileId });
        learned++;
        logLine(log, 'Read from your phone: ' + f.name, 'good');
      } else if (f.media && log) {
        var li = el('li', { class: 'good' }, ['On your phone: ' + f.name + ' ']);
        li.appendChild(el('button', { class: 'ghost small-btn', text: 'Play and listen', onclick: function () { OS.listenToFile(f.fileId, f.name); } }));
        log.appendChild(li);
      }
    });

    if (l.source === 'youtube') {
      logLine(log, 'Searching YouTube for “' + l.query + '”…');
      var yt = await fetchPage('https://www.youtube.com/results?hl=en&search_query=' + encodeURIComponent(l.query));
      var videos = yt.ok ? L.parseYouTube(yt.body) : [];
      if (videos.length) {
        keep({
          kind: 'youtube', title: 'YouTube: ' + l.query, source: 'YouTube',
          text: videos.map(function (v) { return v.title + ' (' + v.channel + '): ' + v.description + ' ' + v.url; }).join('\n'),
        });
        learned++;
        logLine(log, 'Read ' + videos.length + ' videos: “' + videos[0].title + '”…', 'good');
      } else {
        logLine(log, 'Couldn\'t read YouTube right now. Tap “Play it and listen” and I\'ll listen to a video instead.', 'bad');
      }
    } else if (l.source !== 'web') {
      logLine(log, l.sourceLabel + ' needs you signed in to read it, so I\'m reading the web about it instead. Tap “Play it and listen” to listen to it there.');
    }

    var about = l.topic && l.topic !== l.query ? l.query + ' ' + l.topic : l.query;
    logLine(log, 'Searching Arrow Os Google for “' + about + '”…');
    var results = await webSearch(about);
    if (results.length) {
      keep({ kind: 'web', title: 'Search: ' + about, source: 'Arrow Os Google', text: results.map(function (r) { return r.title + ': ' + r.snippet; }).join('\n') });
      learned++;
    }
    var wiki = await wikipedia(l.topic || l.query);
    if (wiki) {
      keep({ kind: 'web', title: 'Wikipedia: ' + wiki.title, source: wiki.url, text: wiki.extract });
      learned++;
      logLine(log, 'Read Wikipedia: ' + wiki.title, 'good');
    }
    var pages = results.filter(function (r) { return !/youtube\.com|facebook\.com|instagram\.com|tiktok\.com/.test(r.url); }).slice(0, 3);
    for (var i = 0; i < pages.length; i++) {
      var r = await fetchPage(pages[i].url);
      if (!r.ok) continue;
      var page = L.htmlToText(r.body);
      if (page.text.length < 200) continue;
      keep({ kind: 'web', title: page.title || pages[i].title, source: pages[i].url, text: page.text.slice(0, 20000) });
      learned++;
      logLine(log, 'Read: ' + (page.title || pages[i].title), 'good');
    }
    save();
    if (learned) logLine(log, 'Done. ' + learned + ' new thing' + (learned === 1 ? '' : 's') + ' in my brain.', 'good');
    else logLine(log, 'I couldn\'t reach the web. Check the internet and try again.', 'bad');
    return learned;
  }

  // ---------- Teach: notes ----------

  $('noteSave').addEventListener('click', function () {
    var t = $('noteInput').value.trim();
    if (!t) return;
    keep({ kind: 'note', title: L.clip(t, 60), text: t, source: 'you' });
    save();
    $('noteInput').value = '';
    toast('Saved. I\'ll follow that.');
    renderSetup();
  });

  // ---------- Tools ----------

  var toolTimer = null;
  $('toolSearch').addEventListener('input', function () {
    clearTimeout(toolTimer);
    toolTimer = setTimeout(renderToolSearch, 200);
  });

  function homeIds() { return JSON.parse(OS.homeTools() || '[]').map(function (t) { return t.id; }); }

  function toolRow(t, onHome) {
    var btn = el('button', { class: 'tool-btn' + (onHome ? ' added' : ''), text: onHome ? 'Remove' : 'Add' });
    btn.addEventListener('click', function () {
      if (onHome) {
        OS.removeTool(t.id);
        brain.tools = brain.tools.filter(function (id) { return id !== t.id; });
      } else {
        OS.addTools(JSON.stringify([t.id]), brain.worker.profession || '');
        if (brain.tools.indexOf(t.id) < 0) brain.tools.push(t.id);
        toast(t.label + ' added to your home screen.');
      }
      save();
      setTimeout(renderTools, 60);
    });
    return el('li', {}, [
      el('span', { class: 'tool-icon', text: t.initial || t.label.charAt(0), style: 'background:' + t.color }),
      el('div', { class: 'tool-text' }, [el('div', { class: 'tool-label', text: t.label }), el('div', { class: 'tool-desc', text: t.description })]),
      btn,
    ]);
  }

  function renderToolSearch() {
    var q = $('toolSearch').value.trim();
    var ids = homeIds();
    var found = q ? JSON.parse(OS.searchTools(q) || '[]') : [];
    if (q) {
      var custom = OS.customTool('', q);
      if (custom) {
        var c = JSON.parse(custom)[0];
        if (!found.some(function (t) { return t.url === c.url; })) found.push(Object.assign(c, { description: 'Add ' + c.url + ' as a website' }));
      } else if (!found.length) {
        $('toolResults').innerHTML = '';
        $('toolResults').appendChild(el('li', {}, [el('div', { class: 'tool-text' }, [
          el('div', { class: 'tool-label', text: 'Nothing called “' + q + '” yet' }),
          el('div', { class: 'tool-desc', text: 'Type its website address (like notion.so) to add it.' }),
        ])]));
        return;
      }
    }
    fill($('toolResults'), found.map(function (t) { return toolRow(t, ids.indexOf(t.id) >= 0); }));
  }

  function renderTools() {
    var ids = homeIds();
    var home = JSON.parse(OS.homeTools() || '[]');
    var prof = brain.worker.profession || '';
    var suggested = prof ? JSON.parse(OS.suggestTools(prof) || '[]') : [];
    var fromProfile = (brain.profile && brain.profile.tools) || [];
    suggested.sort(function (a, b) { return (fromProfile.indexOf(b.id) >= 0) - (fromProfile.indexOf(a.id) >= 0); });
    suggested = suggested.filter(function (t) { return ids.indexOf(t.id) < 0; }).slice(0, 12);
    $('toolSuggestTitle').textContent = prof ? 'Suggested for a ' + prof.toLowerCase() : 'Suggested';
    fill($('toolSuggest'), suggested.map(function (t) { return toolRow(t, false); }));
    fill($('toolHome'), home.length ? home.map(function (t) { return toolRow(t, true); }) : [el('li', { class: 'muted', text: 'Nothing yet. Add tools from the list above.' })]);
    renderToolSearch();
    renderSetup();
  }

  // ---------- Brain ----------

  $('studyMore').addEventListener('click', function () {
    var prof = brain.worker.profession;
    if (!prof) return;
    showTab('teach');
    $('learnInput').value = 'learn about how to be a better ' + prof.toLowerCase();
    learnFrom($('learnInput').value, $('learnLog'));
  });
  $('seeProfile').addEventListener('click', function () {
    researchFromHub = true;
    show('research');
    $('researchBusy').hidden = true;
    $('researchBody').hidden = !brain.profile;
    renderProfile();
  });
  $('changeProfession').addEventListener('click', function () {
    $('professionInput').value = '';
    show('start');
  });

  function forgetItem(id, title) {
    if (!window.confirm('Forget “' + title + '”?')) return;
    L.forget(brain, id);
    save();
    renderBrain();
    renderLessons();
  }

  function renderBrain() {
    var k = brain.knowledge;
    var chars = k.reduce(function (n, x) { return n + (x.text || '').length; }, 0);
    $('brainStats').textContent = k.length + ' things learned · ' + brain.lessons.length + ' lessons · about ' + Math.max(1, Math.round(chars / 6)) + ' words.';
    var items = brain.lessons.slice().reverse().map(lessonItem);
    k.slice().reverse().forEach(function (x) {
      var li = el('li', {}, [
        el('div', { class: 'item-head' }, [
          el('span', { class: 'kind ' + x.kind, text: ({ manual: 'Knows', file: 'File', web: 'Web', youtube: 'YouTube', note: 'Your words', heard: 'Heard', phone: 'Phone' })[x.kind] || x.kind }),
          el('span', { class: 'item-title', text: x.title }),
          x.kind === 'manual' ? null : el('button', { class: 'del', text: 'Forget', onclick: function (e) { e.stopPropagation(); forgetItem(x.id, x.title); } }),
        ]),
        el('div', { class: 'item-meta', text: (x.source ? x.source + ' · ' : '') + (x.addedAt ? ago(x.addedAt) : 'built in') }),
        el('div', { class: 'item-preview', text: L.clip(x.text, 1200) }),
      ]);
      li.addEventListener('click', function () { li.classList.toggle('open'); });
      items.push(li);
    });
    fill($('brainList'), items);
  }

  // ---------- Work ----------

  $('inspoFile').addEventListener('click', function () {
    nativeAsync('pickFiles').then(function (r) {
      var f = (r.files || []).filter(function (x) { return !x.skipped; })[0];
      if (!f) return;
      inspiration = { name: f.name, text: f.text || '', structure: f.text && /<h[1-3]/i.test(f.text) ? L.pageStructure(f.text) : [] };
      $('inspoStatus').textContent = 'Inspiration: ' + f.name;
    });
  });

  async function readInspiration() {
    var url = $('inspoUrl').value.trim();
    if (!url) return inspiration;
    if (!/^https?:\/\//.test(url)) url = 'https://' + url;
    $('inspoStatus').textContent = 'Looking at ' + url + '…';
    var r = await fetchPage(url);
    if (!r.ok) { $('inspoStatus').textContent = 'Couldn\'t open ' + url + '. I\'ll mention it anyway.'; return { url: url }; }
    var page = L.htmlToText(r.body);
    $('inspoStatus').textContent = 'I looked at ' + (page.title || url) + '.';
    return { url: url, structure: L.pageStructure(r.body), text: page.text.slice(0, 600) };
  }

  $('planGo').addEventListener('click', async function () {
    var task = $('taskInput').value.trim();
    if (!task) { toast('Tell me the job first.'); return; }
    $('planGo').disabled = true;
    var insp = await readInspiration();
    $('planGo').disabled = false;
    currentPlan = L.applyDoneBefore(L.plan(task, brain, { inspiration: insp }), brain);
    renderPlan();
    OS.speak(currentPlan.say);
  });

  function renderPlan() {
    var p = currentPlan;
    $('workCompose').hidden = true;
    $('planView').hidden = false;
    $('planSay').textContent = p.say;
    $('needsWrap').hidden = !p.needs.length;
    fill($('planNeeds'), p.needs.map(function (n) {
      var li = el('li', { class: n.done ? 'done' : '' });
      var btns = el('div', { class: 'need-btns' });
      if (n.url) btns.appendChild(el('button', { class: 'ghost', text: 'Open', onclick: function () { OS.open(n.url, n.label); } }));
      btns.appendChild(el('button', { class: 'tick', text: n.before ? 'Done before ✓' : n.done ? 'Done ✓' : 'Done', onclick: function () { n.done = !n.done; n.before = false; L.rememberNeed(brain, n); save(); renderPlan(); } }));
      li.appendChild(el('span', { class: 'tag ' + n.kind, text: n.kind === 'payment' ? 'Pay' : 'Login' }));
      li.appendChild(el('div', { class: 'need-text' }, [el('div', { class: 'need-label', text: n.label }), el('div', { class: 'need-why', text: n.why })]));
      li.appendChild(btns);
      return li;
    }));
    renderSteps();
    $('planUsed').textContent = p.used.length ? 'From my brain: ' + p.used.join(', ') + '.' : '';
    var left = p.needs.filter(function (n) { return !n.done; }).length;
    var running = jobState && jobState.running;
    $('planStart').disabled = left > 0 || running;
    $('planStart').textContent = running ? 'Working…' : 'Start work';
    $('planStartHint').textContent = running ? '' : left ? 'Tap Done on each one first (Done also skips it). ' + left + ' left.' : 'Ready when you are.';
  }

  function renderSteps() {
    var p = currentPlan;
    var at = jobState && jobState.plan === p ? jobState.index : -1;
    var finished = jobState && jobState.plan === p && jobState.finished;
    fill($('planSteps'), p.steps.map(function (s, i) {
      var cls = s.type === 'you' ? 'you' : '';
      if (finished || i < at) cls += ' done-step';
      else if (i === at) cls += ' now';
      return el('li', { class: cls, text: L.describeStep(s) });
    }));
  }

  $('planCancel').addEventListener('click', function () {
    if (jobState && jobState.running) OS.stopJob();
    closePlan();
  });

  function closePlan() {
    $('planView').hidden = true;
    $('workCompose').hidden = false;
    renderHistory();
  }

  $('planStart').addEventListener('click', function () {
    var p = currentPlan;
    if (!p) return;
    jobState = { plan: p, index: 0, running: true, finished: false };
    var steps = p.steps.map(function (s) { return Object.assign({ label: L.describeStep(s) }, s); });
    renderPlan();
    OS.runJob(JSON.stringify({ steps: steps }));
  });

  function onJob(e) {
    if (!jobState) return;
    if (e.event === 'step') {
      jobState.index = e.index;
      renderSteps();
    } else if (e.event === 'pageStep') {
      var step = e.step || {};
      if (step.type === 'learn') learnFrom(step.text, null).then(function () { OS.jobContinue(); });
      else OS.jobContinue();
    } else if (e.event === 'end') {
      jobState.running = false;
      jobState.finished = !!e.finished;
      brain.jobs.unshift({ task: jobState.plan.task, at: Date.now(), finished: !!e.finished, steps: jobState.plan.steps.length });
      brain.jobs = brain.jobs.slice(0, 30);
      save();
      show('hub');
      showTab('work');
      $('workCompose').hidden = true;
      $('planView').hidden = false;
      renderPlan();
      $('planSay').textContent = e.finished ? 'Done ✓' : 'Stopped. Tap Start work to try again.';
      if (e.finished) { $('taskInput').value = ''; inspiration = null; $('inspoUrl').value = ''; $('inspoStatus').textContent = ''; }
    }
  }

  function renderHistory() {
    $('jobHistoryWrap').hidden = !brain.jobs.length;
    fill($('jobHistory'), brain.jobs.slice(0, 8).map(function (j) {
      return el('li', {}, [
        el('div', { text: (j.finished ? '✓ ' : '■ ') + j.task }),
        el('div', { class: 'when', text: (j.finished ? 'Done' : 'Stopped') + ' · ' + ago(j.at) }),
      ]);
    }));
  }

  // ---------- Back button and events from Arrow Os ----------

  window.Cloner = {
    onCallback: function (id, json) {
      var resolve = pending[id];
      delete pending[id];
      var data;
      try { data = JSON.parse(json); } catch (e) { data = {}; }
      if (resolve) resolve(data);
    },
    onEvent: function (name, json) {
      var data;
      try { data = JSON.parse(json); } catch (e) { data = {}; }
      if (name === 'lesson') onLesson(data);
      else if (name === 'heard') onHeard(data);
      else if (name === 'job') onJob(data);
      else if (name === 'shown' && screen === 'hub') showTab(tab);
    },
    /** Arrow Os's Back button. Returns true when the page handled it. */
    back: function () {
      if (screen === 'research') { if (brain.worker.profession) { show('hub'); renderHub(); showTab(researchFromHub ? 'brain' : 'teach'); } else show('start'); return true; }
      if (screen === 'start' && brain.worker.profession) { show('hub'); renderHub(); showTab('work'); return true; }
      if (screen === 'hub' && !$('planView').hidden && !(jobState && jobState.running)) { closePlan(); return true; }
      if (screen === 'hub' && tab !== 'work') { showTab('work'); return true; }
      return false;
    },
  };

  // ---------- Start ----------

  try {
    var acc = JSON.parse(OS.account() || '{}');
    $('startAccount').textContent = acc.email ? 'Signed in as ' + acc.email : '';
  } catch (e) { }

  if (brain.worker.profession) {
    show('hub');
    renderHub();
    showTab(brain.jobs.length ? 'work' : (brain.knowledge.length > 2 || brain.lessons.length ? 'tools' : 'teach'));
  } else {
    show('start');
  }
})();
