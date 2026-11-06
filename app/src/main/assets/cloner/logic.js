/*
 * Arrow Cloner: the brain and the planner of the digital worker.
 *
 * Everything in this file is plain JavaScript with no page or phone code, so it runs
 * the same on the phone and in the tests (node --test tests/cloner).
 *
 * No paid AI is used. The worker:
 *  - knows a set of professions offline and adds what it reads on the web,
 *  - keeps everything it is taught (files, notes, web pages, YouTube, recorded lessons)
 *    as "knowledge" in its brain,
 *  - turns a task into a plan: first the logins and payments it needs from you,
 *    then the steps, written in Arrow Os's own command language.
 */
(function (root) {
  'use strict';

  // ---------- Small text helpers ----------

  var STOP = ('a an and are as at be by for from go has have i in into is it its me my of on or our please so ' +
    'that the their them then this to up us use using was we what when where which who will with you your can ' +
    'do does did make made get also about all any some how want need should would could').split(' ');
  var STOPSET = {};
  STOP.forEach(function (w) { STOPSET[w] = true; });

  function words(text) {
    return String(text || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim().split(' ')
      .filter(function (w) { return w.length > 1 && !STOPSET[w]; })
      .map(stem);
  }

  function stem(w) {
    if (w.length > 5 && /ies$/.test(w)) return w.slice(0, -3) + 'y';
    if (w.length > 4 && /(ing|ers)$/.test(w)) return w.slice(0, -3);
    if (w.length > 4 && /(er|ed|es)$/.test(w)) return w.slice(0, -2);
    if (w.length > 3 && /s$/.test(w) && !/ss$/.test(w)) return w.slice(0, -1);
    return w;
  }

  function clip(text, max) {
    text = String(text || '').replace(/\s+/g, ' ').trim();
    return text.length > max ? text.slice(0, max - 1).replace(/\s+\S*$/, '') + '…' : text;
  }

  function titleCase(s) {
    return String(s || '').trim().replace(/\s+/g, ' ').replace(/\b[a-z]/g, function (c) { return c.toUpperCase(); });
  }

  function decodeEntities(s) {
    return String(s || '')
      .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"').replace(/&#39;|&#x27;|&apos;/g, "'")
      .replace(/&#(\d+);/g, function (_, n) { return String.fromCharCode(+n); })
      .replace(/&#x([0-9a-f]+);/gi, function (_, n) { return String.fromCharCode(parseInt(n, 16)); });
  }

  /** The readable text of an HTML page: scripts, styles and menus removed. */
  function htmlToText(html) {
    var s = String(html || '');
    var title = (s.match(/<title[^>]*>([\s\S]*?)<\/title>/i) || [])[1] || '';
    s = s.replace(/<(script|style|noscript|svg|nav|footer|header|form|iframe)[\s\S]*?<\/\1>/gi, ' ')
      .replace(/<!--[\s\S]*?-->/g, ' ')
      .replace(/<(br|\/p|\/div|\/li|\/h[1-6]|\/tr|\/section|\/article)[^>]*>/gi, '\n')
      .replace(/<li[^>]*>/gi, '\n• ')
      .replace(/<[^>]+>/g, ' ');
    s = decodeEntities(s).split('\n').map(function (l) { return l.replace(/\s+/g, ' ').trim(); })
      .filter(function (l) { return l.length > 2; }).join('\n');
    return { title: decodeEntities(title).replace(/\s+/g, ' ').trim(), text: s };
  }

  /** The headings and sections of a page: what an "inspiration" website is built from. */
  function pageStructure(html) {
    var s = String(html || '');
    var out = [];
    var re = /<(h[1-3]|nav|section|header|footer|form)\b[^>]*>([\s\S]*?)<\/\1>/gi;
    var m;
    while ((m = re.exec(s)) && out.length < 30) {
      var tag = m[1].toLowerCase();
      var text = decodeEntities(m[2].replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();
      if (/^h[1-3]$/.test(tag) && text) out.push(tag.toUpperCase() + ': ' + clip(text, 90));
      else out.push(tag + (text ? ': ' + clip(text, 60) : ''));
    }
    return out.filter(function (l, i, a) { return a.indexOf(l) === i; });
  }

  // ---------- Reading search results ----------

  /** Results from DuckDuckGo's plain HTML page (html.duckduckgo.com/html/?q=...). */
  function parseDuckDuckGo(html) {
    var s = String(html || '');
    var results = [];
    var linkRe = /<a[^>]+class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
    var m;
    while ((m = linkRe.exec(s)) && results.length < 10) {
      var href = decodeEntities(m[1]);
      var uddg = href.match(/[?&]uddg=([^&]+)/);
      var url = uddg ? decodeURIComponent(uddg[1]) : (href.indexOf('//') === 0 ? 'https:' + href : href);
      if (!/^https?:\/\//.test(url) || /duckduckgo\.com\/y\.js/.test(url)) continue; // ads
      var rest = s.slice(linkRe.lastIndex, linkRe.lastIndex + 3000);
      var sn = rest.match(/class="[^"]*result__snippet[^"]*"[^>]*>([\s\S]*?)<\/(a|div|td)>/i);
      results.push({
        title: decodeEntities(m[2].replace(/<[^>]+>/g, '')).replace(/\s+/g, ' ').trim(),
        url: url,
        snippet: sn ? decodeEntities(sn[1].replace(/<[^>]+>/g, '')).replace(/\s+/g, ' ').trim() : '',
      });
    }
    return results;
  }

  /** The videos on a YouTube search page: id, title, channel and description. */
  function parseYouTube(html) {
    var s = String(html || '');
    var start = s.indexOf('ytInitialData');
    if (start < 0) return [];
    var brace = s.indexOf('{', start);
    if (brace < 0) return [];
    // Find the end of the JSON object by counting braces outside strings.
    var depth = 0, inStr = false, esc = false, end = -1;
    for (var i = brace; i < s.length; i++) {
      var c = s[i];
      if (inStr) {
        if (esc) esc = false; else if (c === '\\') esc = true; else if (c === '"') inStr = false;
      } else if (c === '"') inStr = true;
      else if (c === '{') depth++;
      else if (c === '}') { depth--; if (depth === 0) { end = i; break; } }
    }
    if (end < 0) return [];
    var data;
    try { data = JSON.parse(s.slice(brace, end + 1)); } catch (e) { return []; }
    var videos = [];
    var seen = {};
    function runs(x) {
      if (!x) return '';
      if (typeof x.simpleText === 'string') return x.simpleText;
      if (Array.isArray(x.runs)) return x.runs.map(function (r) { return r.text || ''; }).join('');
      return '';
    }
    (function walk(node, depthLeft) {
      if (!node || typeof node !== 'object' || depthLeft <= 0 || videos.length >= 12) return;
      var v = node.videoRenderer || node.compactVideoRenderer || node.videoWithContextRenderer;
      if (v && v.videoId && !seen[v.videoId]) {
        seen[v.videoId] = true;
        var desc = '';
        if (v.detailedMetadataSnippets && v.detailedMetadataSnippets[0]) desc = runs(v.detailedMetadataSnippets[0].snippetText);
        if (!desc) desc = runs(v.descriptionSnippet);
        videos.push({
          id: v.videoId,
          title: runs(v.title) || runs(v.headline),
          channel: runs(v.ownerText) || runs(v.longBylineText) || runs(v.shortBylineText),
          description: desc,
          url: 'https://m.youtube.com/watch?v=' + v.videoId,
        });
      }
      for (var k in node) if (Object.prototype.hasOwnProperty.call(node, k)) walk(node[k], depthLeft - 1);
    })(data, 40);
    return videos;
  }

  // ---------- Professions it already knows ----------

  function acct(label, url, kind, why) { return { label: label, url: url, kind: kind, why: why }; }

  var PROFESSIONS = [
    {
      key: 'website developer', name: 'Website developer',
      keywords: ['website', 'web developer', 'web designer', 'web development', 'developer', 'programmer', 'coder', 'software', 'frontend', 'full stack', 'site builder'],
      summary: 'A website developer plans, builds and publishes websites for people and businesses: the pages, the pictures and text, the domain name, the hosting, and getting the site found on Google.',
      duties: ['Collect the client\'s photos, logo, text and wishes', 'Design and build the pages', 'Buy or connect a domain name', 'Put the site on a hosting server', 'Get it indexed on Google and set up a Google Business Profile', 'Fix and update the site later'],
      skills: ['HTML, CSS and JavaScript (or an AI builder like Claude Code)', 'Good layout and design sense', 'Domains and DNS', 'Hosting and deployment', 'Search engine basics (SEO)'],
      tools: ['claude', 'github', 'vercel', 'codepen', 'mdn', 'canva', 'gbusiness', 'whatsapp', 'gmail'],
      accounts: [
        acct('Claude plan (for Claude Code)', 'https://claude.ai/upgrade', 'payment', 'Claude Code needs a paid Claude plan.'),
        acct('Domain name', 'https://www.namecheap.com/', 'payment', 'The website\'s address, for example sarahsbakery.com. You can skip this and use the free address the host gives.'),
        acct('Hosting (Netlify, free)', 'https://app.netlify.com/signup', 'login', 'Where the website lives on the internet. The free plan is enough.'),
        acct('Google account', 'https://search.google.com/search-console', 'login', 'To get the site indexed on Google and to make the Business Profile.'),
      ],
    },
    {
      key: 'graphic designer', name: 'Graphic designer',
      keywords: ['graphic', 'designer', 'design', 'logo', 'poster', 'flyer', 'branding', 'illustrator'],
      summary: 'A graphic designer makes logos, posters, flyers, social media posts and brand looks that catch attention and say the right thing.',
      duties: ['Understand what the client wants to say', 'Make drafts of logos, posters and posts', 'Pick colours, fonts and pictures', 'Send files in the right sizes'],
      skills: ['Layout, colour and type', 'Canva, Figma or Photopea', 'Taking feedback'],
      tools: ['canva', 'figma', 'photopea', 'removebg', 'pinterest', 'behance', 'unsplash', 'whatsapp'],
      accounts: [acct('Canva account', 'https://www.canva.com/login', 'login', 'Most designs are made in Canva (free plan is enough to start).')],
    },
    {
      key: 'social media manager', name: 'Social media manager',
      keywords: ['social media', 'marketer', 'marketing', 'influencer', 'content creator', 'content', 'tiktok', 'instagram', 'facebook', 'manager'],
      summary: 'A social media manager plans, makes and posts content for a brand, answers comments and messages, and grows followers and sales.',
      duties: ['Plan a posting calendar', 'Make posts, reels and captions', 'Post on Facebook, Instagram and TikTok', 'Reply to comments and messages', 'Check what works'],
      skills: ['Writing captions', 'Short video editing', 'Knowing each platform', 'Reading stats'],
      tools: ['meta_business', 'canva', 'capcut', 'buffer', 'instagram', 'facebook', 'tiktok', 'whatsapp_business'],
      accounts: [acct('Meta Business Suite', 'https://business.facebook.com/', 'login', 'To post on Facebook and Instagram for the business.'),
        acct('TikTok account', 'https://www.tiktok.com/login', 'login', 'To post videos on TikTok.')],
    },
    {
      key: 'photographer', name: 'Photographer',
      keywords: ['photographer', 'photography', 'photo', 'photos', 'camera', 'studio'],
      summary: 'A photographer takes, edits and delivers photos for clients, events and businesses, and shows their work to win new clients.',
      duties: ['Book shoots with clients', 'Take and choose the best photos', 'Edit colours and light', 'Deliver albums online', 'Post the best work'],
      skills: ['Light and composition', 'Photo editing', 'Client care'],
      tools: ['lightroom', 'photopea', 'gphotos', 'instagram', 'calendly', 'whatsapp', 'gdrive'],
      accounts: [acct('Google account (Drive/Photos)', 'https://accounts.google.com/', 'login', 'To deliver albums to clients.')],
    },
    {
      key: 'accountant', name: 'Accountant',
      keywords: ['accountant', 'accounting', 'bookkeeper', 'bookkeeping', 'finance', 'tax', 'auditor'],
      summary: 'An accountant keeps a business\'s money records, prepares invoices, reports and taxes, and helps the owner make money decisions.',
      duties: ['Record income and expenses', 'Send invoices and follow up', 'Prepare monthly reports', 'Prepare tax returns'],
      skills: ['Bookkeeping', 'Spreadsheets', 'Tax rules where you work'],
      tools: ['gsheets', 'wave', 'quickbooks', 'xero', 'gmail', 'gdrive'],
      accounts: [acct('Wave (free accounting)', 'https://www.waveapps.com/', 'login', 'Free invoices and books.')],
    },
    {
      key: 'teacher', name: 'Teacher',
      keywords: ['teacher', 'tutor', 'lecturer', 'teaching', 'school', 'education', 'trainer'],
      summary: 'A teacher plans lessons, explains topics, sets and marks work, and keeps parents and students informed.',
      duties: ['Plan lessons', 'Make notes, slides and quizzes', 'Mark work and give feedback', 'Talk with parents'],
      skills: ['Explaining clearly', 'Planning', 'Using Google Classroom or similar'],
      tools: ['gclassroom', 'gdocs', 'gforms', 'kahoot', 'quizlet', 'khan', 'youtube'],
      accounts: [acct('Google account', 'https://classroom.google.com/', 'login', 'For Classroom, Docs and Forms.')],
    },
    {
      key: 'writer', name: 'Writer',
      keywords: ['writer', 'author', 'copywriter', 'blogger', 'journalist', 'editor', 'writing'],
      summary: 'A writer researches and writes articles, blogs, books or adverts that people want to read.',
      duties: ['Research the topic', 'Write drafts', 'Edit and check facts', 'Publish or send to the client'],
      skills: ['Clear writing', 'Research', 'Editing'],
      tools: ['gdocs', 'grammarly', 'hemingway', 'medium', 'substack', 'wordpress', 'claude'],
      accounts: [acct('Google account', 'https://docs.google.com/', 'login', 'To write in Google Docs.')],
    },
    {
      key: 'online seller', name: 'Online seller',
      keywords: ['seller', 'shop', 'store', 'ecommerce', 'e commerce', 'retail', 'boutique', 'vendor', 'trader', 'sales'],
      summary: 'An online seller lists products, answers buyers, takes payments and delivers orders through a shop, marketplace or social media.',
      duties: ['Take good product photos', 'List products with prices', 'Answer buyers on WhatsApp and social media', 'Take payments', 'Deliver orders'],
      skills: ['Product photos', 'Customer care', 'Pricing'],
      tools: ['whatsapp_business', 'fb_marketplace', 'shopify', 'instagram', 'canva', 'paypal'],
      accounts: [acct('WhatsApp Business', 'https://web.whatsapp.com/', 'login', 'To chat with buyers.')],
    },
    {
      key: 'video editor', name: 'Video editor',
      keywords: ['video editor', 'editor', 'video', 'youtuber', 'filmmaker', 'videographer'],
      summary: 'A video editor cuts raw clips into finished videos with music, text and effects for YouTube, TikTok and clients.',
      duties: ['Collect the clips', 'Cut and arrange them', 'Add music, text and colour', 'Export and upload'],
      skills: ['Editing software', 'Story and timing', 'Sound'],
      tools: ['capcut', 'clipchamp', 'yt_studio', 'youtube', 'tiktok', 'gdrive'],
      accounts: [acct('YouTube channel', 'https://studio.youtube.com/', 'login', 'To upload videos.')],
    },
    {
      key: 'lawyer', name: 'Lawyer',
      keywords: ['lawyer', 'advocate', 'attorney', 'legal', 'law', 'paralegal'],
      summary: 'A lawyer advises clients, researches the law, drafts documents and represents clients.',
      duties: ['Meet and advise clients', 'Research cases and laws', 'Draft contracts and letters', 'Keep deadlines'],
      skills: ['Legal research', 'Drafting', 'Arguing a case'],
      tools: ['justia', 'findlaw', 'gdocs', 'docusign', 'gcalendar', 'gmail'],
      accounts: [acct('DocuSign', 'https://www.docusign.com/', 'login', 'To get documents signed online.')],
    },
  ];

  var GENERIC_PROFESSION = {
    key: '', name: '',
    summary: '',
    duties: ['Talk with clients and understand what they need', 'Do the work and keep records', 'Deliver on time', 'Find new clients online'],
    skills: ['Knowledge of the field', 'Communication', 'Using online tools'],
    tools: ['gmail', 'gdrive', 'whatsapp', 'gcalendar', 'claude'],
    accounts: [acct('Google account', 'https://accounts.google.com/', 'login', 'Most online tools sign in with Google.')],
  };

  /** What the worker already knows about [name] (offline), before reading the web. */
  function professionProfile(name) {
    var q = ' ' + String(name || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim() + ' ';
    var qWords = words(name);
    var best = null, bestScore = 0;
    PROFESSIONS.forEach(function (p) {
      var score = 0;
      p.keywords.forEach(function (k) {
        if (q.indexOf(' ' + k + ' ') >= 0) score += k.split(' ').length * 3;
        else if (words(k).some(function (w) { return qWords.indexOf(w) >= 0; })) score += 1;
      });
      if (score > bestScore) { bestScore = score; best = p; }
    });
    var base = bestScore >= 2 ? best : GENERIC_PROFESSION;
    var display = titleCase(name) || base.name;
    return {
      name: display,
      known: base !== GENERIC_PROFESSION,
      matched: base.name,
      summary: base.summary || ('A ' + display.toLowerCase() + ' — I will read about this work on the web and you can teach me the rest.'),
      duties: base.duties.slice(),
      skills: base.skills.slice(),
      tools: base.tools.slice(),
      accounts: base.accounts.map(function (a) { return Object.assign({}, a); }),
      sources: [],
    };
  }

  /** Adds what the web said (Wikipedia summary, search results) to the profile. */
  function addResearch(profile, research) {
    var p = Object.assign({}, profile);
    research = research || {};
    if (research.wikipedia && research.wikipedia.extract) {
      p.summary = research.wikipedia.extract;
      p.sources = (p.sources || []).concat([{ title: 'Wikipedia: ' + research.wikipedia.title, url: research.wikipedia.url }]);
    }
    (research.results || []).slice(0, 6).forEach(function (r) {
      p.sources = (p.sources || []).concat([{ title: r.title, url: r.url, snippet: r.snippet }]);
    });
    var reqText = (research.requirements || []).map(function (r) { return r.snippet; }).join(' ');
    var extra = [];
    var reqRe = /\b(degree|diploma|certificate|certification|licen[cs]e|experience|portfolio|bachelor'?s?)\b[^.]{0,80}/gi;
    var m;
    while ((m = reqRe.exec(reqText)) && extra.length < 4) extra.push(clip(m[0], 90));
    p.requirements = extra;
    return p;
  }

  // ---------- The brain ----------

  /** What every worker knows from the start: how to use Arrow Os, its home. */
  var ARROW_OS_MANUAL = [
    'Arrow Os is my home. I do all my work inside it. Commands start with "tap" or "search".',
    'search <words> — searches the web (inside YouTube, TikTok, Facebook or Instagram it searches that app).',
    'search <thing> on YouTube — searches one app. search claude.ai — opens a website.',
    'tap open <app or website> — opens YouTube, Facebook, Instagram, TikTok, 9apps, any app on the home screen, or any address like claude.ai.',
    'tap home · tap back · tap reload · tap previous (every tab opened) · tap my files (Arrow Files).',
    'tap <button name> — taps a button or link on the page by its name, for example tap sign in, tap share, tap subscribe.',
    'tap type <words> — types into the text box on the page. tap type <words> and send — types and sends.',
    'tap scroll down · tap scroll to the top · tap next video · tap play the second video · tap pause.',
    'Everything downloaded inside Arrow Os is saved in Arrow Files, in the user\'s own folder, with a copy in the cloud.',
    'Websites open inside Arrow Os; logins stay signed in.',
  ].join('\n');

  function newBrain() {
    return {
      version: 1,
      worker: { name: 'Arrow Cloner', profession: '', createdAt: 0 },
      profile: null,
      tools: [],
      knowledge: [{ id: 'arrow-os-manual', kind: 'manual', title: 'How to use Arrow Os', text: ARROW_OS_MANUAL, source: 'built in', addedAt: 0 }],
      lessons: [],
      jobs: [],
    };
  }

  function loadBrain(raw) {
    var b;
    try { b = raw ? JSON.parse(raw) : null; } catch (e) { b = null; }
    var fresh = newBrain();
    if (!b || typeof b !== 'object') return fresh;
    b.worker = Object.assign(fresh.worker, b.worker || {});
    b.tools = Array.isArray(b.tools) ? b.tools : [];
    b.knowledge = Array.isArray(b.knowledge) ? b.knowledge : [];
    b.lessons = Array.isArray(b.lessons) ? b.lessons : [];
    b.jobs = Array.isArray(b.jobs) ? b.jobs : [];
    // The Arrow Os manual is always there and always up to date.
    b.knowledge = b.knowledge.filter(function (k) { return k.id !== 'arrow-os-manual'; });
    b.knowledge.unshift(fresh.knowledge[0]);
    b.version = 1;
    return b;
  }

  var MAX_ITEM_CHARS = 200000;
  var idCounter = 0;
  function newId(prefix) {
    idCounter = (idCounter + 1) % 100000;
    return prefix + '-' + Date.now().toString(36) + '-' + idCounter.toString(36);
  }

  /** Adds something the worker learned. Same title and source replaces the old copy. */
  function learn(brain, item) {
    var k = {
      id: item.id || newId('k'),
      kind: item.kind || 'note',
      title: clip(item.title || 'Untitled', 120),
      text: String(item.text || '').slice(0, MAX_ITEM_CHARS),
      source: item.source || '',
      fileId: item.fileId || null,
      addedAt: item.addedAt || Date.now(),
    };
    brain.knowledge = brain.knowledge.filter(function (x) {
      return !(x.title === k.title && x.source === k.source && x.kind === k.kind);
    });
    brain.knowledge.push(k);
    return k;
  }

  function forget(brain, id) {
    if (id === 'arrow-os-manual') return false;
    var before = brain.knowledge.length + brain.lessons.length;
    brain.knowledge = brain.knowledge.filter(function (k) { return k.id !== id; });
    brain.lessons = brain.lessons.filter(function (l) { return l.id !== id; });
    return brain.knowledge.length + brain.lessons.length < before;
  }

  /** The pieces of knowledge that fit [task] best, with the best few sentences of each. */
  function recall(brain, task, max) {
    var tw = words(task);
    if (!tw.length) return [];
    var scored = [];
    brain.knowledge.forEach(function (k) {
      if (k.kind === 'manual' || /kept in Arrow Files for jobs\)$/.test(k.text || '')) return; // pictures and videos have no words
      var titleWords = words(k.title);
      var score = 0;
      tw.forEach(function (w) { if (titleWords.indexOf(w) >= 0) score += 3; });
      var sentences = String(k.text).replace(/([.!?])\s+/g, '$1\n').split('\n').filter(function (s) { return s.length > 20; });
      var best = sentences.map(function (s) {
        var sw = words(s);
        var hits = tw.filter(function (w) { return sw.indexOf(w) >= 0; }).length;
        return { s: s.trim(), hits: hits };
      }).filter(function (x) { return x.hits > 0; })
        .sort(function (a, b) { return b.hits - a.hits; }).slice(0, 3);
      best.forEach(function (x) { score += x.hits; });
      if (k.kind === 'note' || k.kind === 'file') score += score > 0 ? 1 : 0; // the teacher's own words count a bit more
      if (score > 0) scored.push({ item: k, score: score, lines: best.map(function (x) { return clip(x.s, 300); }) });
    });
    scored.sort(function (a, b) { return b.score - a.score; });
    return scored.slice(0, max || 4);
  }

  /** The lesson (recorded while the teacher worked) that matches [task], or null. */
  function findLesson(brain, task) {
    var tw = words(task);
    var best = null, bestScore = 0;
    brain.lessons.forEach(function (l) {
      var lw = words(l.title);
      if (!lw.length) return;
      var hits = lw.filter(function (w) { return tw.indexOf(w) >= 0; }).length;
      var score = hits / lw.length;
      if (hits >= 1 && score > bestScore) { bestScore = score; best = l; }
    });
    return bestScore >= 0.6 ? best : null;
  }

  /**
   * Turns what Arrow Os recorded during a lesson into steps the worker can repeat.
   * Events are {kind: 'cmd', text} for every command and {kind: 'open', url, title} for
   * every page opened. A page opened right after a command was opened BY that command.
   */
  function lessonSteps(events) {
    var steps = [];
    var lastCmdAt = -1e12;
    (events || []).forEach(function (e) {
      if (e.kind === 'cmd' && e.text) {
        steps.push({ type: 'cmd', text: e.text });
        lastCmdAt = e.at || 0;
      } else if (e.kind === 'open' && e.url && /^https?:/.test(e.url)) {
        if ((e.at || 0) - lastCmdAt < 6000) return;
        var prev = steps[steps.length - 1];
        if (prev && prev.type === 'open' && prev.url === e.url) return;
        steps.push({ type: 'open', url: e.url, title: e.title || hostOf(e.url) });
      }
    });
    return steps;
  }

  function hostOf(url) {
    var m = String(url || '').match(/^https?:\/\/([^/?#]+)/i);
    return m ? m[1].replace(/^www\./, '') : '';
  }

  // ---------- "Go to YouTube and learn ..." ----------

  var SOURCES = {
    youtube: { label: 'YouTube', re: /\b(youtube|you tube|yt)\b/ },
    tiktok: { label: 'TikTok', re: /\b(tiktok|tik tok)\b/ },
    facebook: { label: 'Facebook', re: /\b(facebook|fb)\b/ },
    instagram: { label: 'Instagram', re: /\b(instagram|insta|ig)\b/ },
  };

  /**
   * Understands a learning order, for example
   * "go to YouTube search rich dad poor dad by Robert Kiyosaki and learn about financial literacy".
   * Returns {source, query, topic}.
   */
  function parseLearn(text) {
    var t = ' ' + String(text || '').toLowerCase().replace(/[“”"]/g, '').replace(/\s+/g, ' ').trim() + ' ';
    var source = 'web';
    Object.keys(SOURCES).some(function (k) { if (SOURCES[k].re.test(t)) { source = k; return true; } return false; });
    var topic = '';
    var tm = t.match(/\b(?:and )?(?:learn|study|understand|read up on|find out)(?: (?:about|on|how to|from it about))? (.+?)\s*$/);
    if (tm) topic = tm[1];
    var query = '';
    var qm = t.match(/\b(?:search(?: for)?|look up|find|watch|about) (.+?)(?= and (?:learn|study|understand)| then | on (?:youtube|tiktok|facebook|instagram)\b|\s*$)/);
    if (qm) query = qm[1];
    if (!query) query = topic;
    if (!query) {
      query = t.replace(/\b(go to|open|on|in|at|youtube|you tube|tiktok|facebook|instagram|and|learn|study|from|someone|social media)\b/g, ' ');
    }
    query = query.replace(/^(?:for|about|on) /, '').replace(/\s+/g, ' ').trim();
    topic = (topic || query).replace(/\s+/g, ' ').trim();
    return { source: source, sourceLabel: source === 'web' ? 'the web' : SOURCES[source].label, query: query, topic: topic };
  }

  function sourceSearchUrl(source, query) {
    var q = encodeURIComponent(query);
    switch (source) {
      case 'youtube': return 'https://m.youtube.com/results?search_query=' + q;
      case 'tiktok': return 'https://www.tiktok.com/search?q=' + q;
      case 'facebook': return 'https://m.facebook.com/search/top/?q=' + q;
      case 'instagram': return 'https://www.instagram.com/explore/search/keyword/?q=' + q;
      default: return 'https://www.google.com/search?q=' + q;
    }
  }

  // ---------- The planner ----------

  var SITES = [
    { key: 'whatsapp', re: /\bwhat'?s ?app\b/, label: 'WhatsApp Web', url: 'https://web.whatsapp.com/', login: true },
    { key: 'gmail', re: /\b(gmail|e-?mail|inbox)\b/, label: 'Gmail', url: 'https://mail.google.com/', login: true },
    { key: 'claude', re: /\bclaude( code)?\b/, label: 'Claude Code', url: 'https://claude.ai/code', login: true },
    { key: 'youtube', re: /\b(youtube|you tube)\b/, label: 'YouTube', url: 'https://m.youtube.com/', login: false },
    { key: 'facebook', re: /\b(facebook|fb)\b/, label: 'Facebook', url: 'https://m.facebook.com/', login: true },
    { key: 'instagram', re: /\b(instagram|insta)\b/, label: 'Instagram', url: 'https://www.instagram.com/', login: true },
    { key: 'tiktok', re: /\b(tiktok|tik tok)\b/, label: 'TikTok', url: 'https://www.tiktok.com/', login: true },
    { key: 'canva', re: /\bcanva\b/, label: 'Canva', url: 'https://www.canva.com/', login: true },
    { key: 'drive', re: /\b(google )?drive\b/, label: 'Google Drive', url: 'https://drive.google.com/', login: true },
    { key: 'linkedin', re: /\blinkedin\b/, label: 'LinkedIn', url: 'https://www.linkedin.com/', login: true },
    { key: 'github', re: /\bgithub\b/, label: 'GitHub', url: 'https://github.com/', login: true },
  ];

  function need(id, label, url, kind, why) { return { id: id, label: label, url: url, kind: kind, why: why, done: false }; }

  /** Who sent the material: "the images Sarah has sent", "photos from Sarah". */
  function senderOf(task) {
    var m = String(task).match(/\b([A-Z][a-z]+)(?: has| have| had)? sent\b/) ||
      String(task).match(/\b(?:from|by|of) ([A-Z][a-z]+)\b/);
    if (m && !/^(Whatsapp|Claude|Google|Youtube|Facebook|Instagram|Tiktok|Arrow)$/i.test(m[1])) return m[1];
    return '';
  }

  function detectIntent(t) {
    if (/\b(website|web site|site|web ?page|landing page|portfolio|online shop|online store)\b/.test(t) &&
      /\b(make|build|create|design|develop|set up|setup|code)\b/.test(t)) return 'website';
    if (/\b(logo|poster|flyer|banner|design|thumbnail|business card)\b/.test(t) && /\b(make|create|design)\b/.test(t)) return 'design';
    if (/\b(post|upload|publish|share)\b/.test(t) && /\b(facebook|instagram|tiktok|youtube|status)\b/.test(t)) return 'post';
    if (/\b(send|write|reply|answer)\b/.test(t) && /\b(email|e-mail|gmail|mail)\b/.test(t)) return 'email';
    if (/\b(research|find out|learn|study|look up|search)\b/.test(t)) return 'research';
    return 'general';
  }

  /** The text the worker types into Claude Code, built from the task and the brain. */
  function claudePrompt(task, brain, notes, inspiration, sender) {
    var lines = [];
    lines.push('Task from my client: ' + task.trim());
    var prof = brain.worker && brain.worker.profession;
    if (prof) lines.push('You are helping a ' + prof.toLowerCase() + '.');
    if (sender) lines.push('The photos and videos ' + sender + ' sent are attached (from Arrow Files). Use them on the site.');
    if (inspiration) {
      lines.push('Inspiration: ' + (inspiration.url || inspiration.name || ''));
      if (inspiration.structure && inspiration.structure.length) {
        lines.push('Follow a similar structure: ' + inspiration.structure.slice(0, 12).join(' | '));
      }
      if (inspiration.text) lines.push('Notes from the inspiration: ' + clip(inspiration.text, 500));
    }
    if (notes.length) {
      lines.push('Follow my own instructions and knowledge:');
      notes.forEach(function (n) { n.lines.forEach(function (l) { lines.push('- ' + l); }); });
    }
    lines.push('Build a complete, mobile-friendly website as plain HTML, CSS and JavaScript (an index.html and its files) that I can upload to Netlify. Ask me if anything important is missing.');
    return clip(lines.join(' '), 3500);
  }

  /**
   * Plans [task]. Returns
   *   say:   what the worker answers first ("I'm on it ..."),
   *   needs: logins and payments to do before work starts (each opens in Arrow Os),
   *   steps: what the worker will do, in order. Step types:
   *     open {url, title}            open a site inside Arrow Os
   *     cmd {text}                   an Arrow Os command ("tap ...", "search ...")
   *     type {text, submit}          type into the text box on the page
   *     you {text}                   your turn: something only a person can do (pick photos, pay, scan a QR code)
   *     wait {seconds}
   *     say {text}                   speak to the user
   *   used:  the knowledge and lesson used, so the user can see why.
   */
  function plan(task, brain, options) {
    options = options || {};
    var text = String(task || '').trim();
    var t = text.toLowerCase();
    var notes = recall(brain, text, 4);
    var lesson = findLesson(brain, text);
    var sender = senderOf(text);
    var needs = [];
    var steps = [];
    var used = notes.map(function (n) { return n.item.title; });
    var inspiration = options.inspiration || null;

    function addNeed(n) { if (!needs.some(function (x) { return x.id === n.id; })) needs.push(n); }
    var mentioned = SITES.filter(function (s) { return s.re.test(t); });
    mentioned.forEach(function (s) {
      if (s.login) addNeed(need('login-' + s.key, 'Sign in to ' + s.label, s.url, 'login', 'I work inside your ' + s.label + ', so you sign in once and Arrow Os keeps you signed in.'));
    });

    if (lesson) {
      used.unshift('Lesson: ' + lesson.title);
      steps.push({ type: 'say', text: 'I learned this from you: ' + lesson.title + '. Doing it the same way.' });
      lesson.steps.forEach(function (s) { steps.push(s); });
    }

    var intent = lesson ? 'lesson' : detectIntent(t);

    if (intent === 'website') {
      addNeed(need('login-claude', 'Sign in to Claude Code', 'https://claude.ai/code', 'login', 'I build the website with Claude Code.'));
      addNeed(need('pay-claude', 'Claude plan for Claude Code', 'https://claude.ai/upgrade', 'payment', 'Claude Code needs a paid Claude plan. Skip if you already have one.'));
      var wantsDomain = !/\b(no domain|without (a )?domain|free address)\b/.test(t);
      if (wantsDomain) addNeed(need('pay-domain', 'Buy a domain name', 'https://www.namecheap.com/', 'payment', 'The website\'s address. Skip it to use the free Netlify address.'));
      addNeed(need('login-hosting', 'Sign in to Netlify (free hosting)', 'https://app.netlify.com/signup', 'login', 'Where the website will live. Free.'));
      var wantsGoogle = /\b(index|google|business profile|seo|search console|found)\b/.test(t) || !/\b(only|just) (make|build)\b/.test(t);
      if (wantsGoogle) addNeed(need('login-google', 'Sign in to Google', 'https://search.google.com/search-console', 'login', 'To index the site on Google and make the Business Profile.'));

      if (/what'?s ?app/.test(t)) {
        steps.push({ type: 'open', url: 'https://web.whatsapp.com/', title: 'WhatsApp Web' });
        steps.push({ type: 'you', text: 'Open the chat' + (sender ? ' with ' + sender : '') + ' and download the photos and videos' + (sender ? ' ' + sender + ' sent' : '') + '. They are saved in Arrow Files. Tap Continue when done.' });
      }
      steps.push({ type: 'open', url: 'https://claude.ai/code', title: 'Claude Code' });
      steps.push({ type: 'wait', seconds: 4 });
      steps.push({ type: 'type', text: claudePrompt(text, brain, notes, inspiration, sender), submit: false });
      steps.push({ type: 'you', text: 'Attach the photos from Arrow Files with the + button, check the message, then send it. When Claude has built the site, download it (Arrow Files keeps it) and tap Continue.' });
      steps.push({ type: 'open', url: 'https://app.netlify.com/drop', title: 'Netlify Drop' });
      steps.push({ type: 'you', text: 'Pick the website folder from Arrow Files to put it online. Copy the address Netlify gives you, then tap Continue.' });
      if (wantsDomain) {
        steps.push({ type: 'open', url: 'https://app.netlify.com/', title: 'Netlify' });
        steps.push({ type: 'you', text: 'Open the site → Domain management → Add a domain, and type the domain you bought. Follow Netlify\'s steps to point it. Tap Continue when done.' });
      }
      if (wantsGoogle) {
        steps.push({ type: 'open', url: 'https://search.google.com/search-console', title: 'Google Search Console' });
        steps.push({ type: 'you', text: 'Add the website address, verify it, then use URL inspection → Request indexing. Tap Continue when done.' });
        steps.push({ type: 'open', url: 'https://business.google.com/create', title: 'Google Business Profile' });
        steps.push({ type: 'you', text: 'Type the business name, category and the new website address, then finish the profile. Tap Continue when done.' });
      }
    } else if (intent === 'design') {
      addNeed(need('login-canva', 'Sign in to Canva', 'https://www.canva.com/login', 'login', 'I design in Canva (free plan).'));
      if (/what'?s ?app/.test(t)) {
        steps.push({ type: 'open', url: 'https://web.whatsapp.com/', title: 'WhatsApp Web' });
        steps.push({ type: 'you', text: 'Download the pictures' + (sender ? ' ' + sender + ' sent' : '') + ' so they are in Arrow Files. Tap Continue.' });
      }
      var what = (t.match(/\b(logo|poster|flyer|banner|thumbnail|business card)\b/) || ['', 'design'])[1];
      steps.push({ type: 'open', url: 'https://www.canva.com/search/templates?q=' + encodeURIComponent(what), title: 'Canva' });
      steps.push({ type: 'you', text: 'Pick a ' + what + ' template you like and tap Continue. I will keep going from there next time once you teach me with a recording.' });
    } else if (intent === 'post') {
      var target = mentioned.filter(function (s) { return ['facebook', 'instagram', 'tiktok', 'youtube'].indexOf(s.key) >= 0; })[0];
      if (target) {
        steps.push({ type: 'open', url: target.url, title: target.label });
        steps.push({ type: 'cmd', text: 'tap create' });
        steps.push({ type: 'you', text: 'Pick the photo or video from Arrow Files, add the caption, then post. Tap Continue when done.' });
      }
    } else if (intent === 'email') {
      steps.push({ type: 'open', url: 'https://mail.google.com/mail/mu/mp/#co', title: 'Gmail' });
      steps.push({ type: 'you', text: 'Check who it goes to and the message, then send. Tap Continue when done.' });
    } else if (intent === 'research') {
      var q = text.replace(/^(please )?(go and |go to \w+ and )?(research|find out|learn|study|look up|search)( about| for| on)? /i, '');
      steps.push({ type: 'learn', text: q });
      steps.push({ type: 'cmd', text: 'search ' + q });
    } else if (intent === 'general') {
      mentioned.forEach(function (s) { steps.push({ type: 'open', url: s.url, title: s.label }); });
      if (!mentioned.length) steps.push({ type: 'cmd', text: 'search ' + text });
      steps.push({ type: 'you', text: 'I don\'t know this job yet. Do it once while I record (Teach → Record me while I work) and next time I will do it myself. Tap Continue when done.' });
    }

    if (/\b(pay|payment|buy|purchase|subscribe|subscription)\b/.test(t) && !needs.some(function (n) { return n.kind === 'payment'; })) {
      addNeed(need('pay-other', 'The payment for this job', '', 'payment', 'This job costs money. Pay it yourself; I never pay for you.'));
    }

    steps.push({ type: 'say', text: 'Done.' });
    var say = 'I\'m on it.';
    if (needs.length) say += ' First I need ' + (needs.length === 1 ? 'one thing' : needs.length + ' things') + ' from you: logins and payments are always yours to do.';
    return { task: text, intent: intent, say: say, needs: needs, steps: steps, used: used, lesson: lesson ? lesson.id : null };
  }

  /** A short, human line for a step, used on the screen and in the progress bar. */
  function describeStep(s) {
    switch (s.type) {
      case 'open': return 'Open ' + (s.title || hostOf(s.url));
      case 'cmd': return s.text;
      case 'type': return 'Type: ' + clip(s.text, 80);
      case 'you': return 'Your turn: ' + s.text;
      case 'wait': return 'Wait ' + s.seconds + ' s';
      case 'say': return 'Say: ' + s.text;
      case 'learn': return 'Learn about ' + s.text;
      default: return s.type;
    }
  }

  var api = {
    words: words, clip: clip, titleCase: titleCase, htmlToText: htmlToText, pageStructure: pageStructure,
    parseDuckDuckGo: parseDuckDuckGo, parseYouTube: parseYouTube,
    PROFESSIONS: PROFESSIONS, professionProfile: professionProfile, addResearch: addResearch,
    ARROW_OS_MANUAL: ARROW_OS_MANUAL, newBrain: newBrain, loadBrain: loadBrain, learn: learn, forget: forget,
    recall: recall, findLesson: findLesson, lessonSteps: lessonSteps, hostOf: hostOf,
    parseLearn: parseLearn, sourceSearchUrl: sourceSearchUrl,
    senderOf: senderOf, detectIntent: detectIntent, plan: plan, describeStep: describeStep, claudePrompt: claudePrompt,
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ClonerLogic = api;
})(this);
