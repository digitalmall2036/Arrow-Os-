/*
 * Arrow Os command language: "tap" and "search".
 *
 * A JavaScript port of the Android app's CommandParser.kt / ArrowApp.kt / WebLinks.kt,
 * so both versions understand exactly the same commands. Pure JavaScript with no
 * Electron or DOM types: it runs in the app window and in the Node unit tests.
 */
(function (root) {
  'use strict';

  // ---------- helpers ----------

  function removePrefix(s, p) { return s.startsWith(p) ? s.slice(p.length) : s; }
  function removeSuffix(s, p) { return s.endsWith(p) ? s.slice(0, s.length - p.length) : s; }
  function escapeRegex(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
  function distinct(list) { return list.filter((v, i) => list.indexOf(v) === i); }

  /** Like Java's URLEncoder: spaces become "+", and !'()* are escaped too. */
  function enc(s) {
    return encodeURIComponent(String(s).trim())
      .replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase())
      .replace(/%20/g, '+');
  }

  // ---------- the apps inside Arrow Os ----------

  /** The five apps. On Windows they open as the full desktop websites. */
  const APPS = [
    {
      id: 'YOUTUBE', label: 'YouTube', homeUrl: 'https://www.youtube.com/', color: '#FF0033',
      aliases: ['youtube', 'you tube', 'yt'],
      search: (q) => 'https://www.youtube.com/results?search_query=' + enc(q),
    },
    {
      id: 'FACEBOOK', label: 'Facebook', homeUrl: 'https://www.facebook.com/', color: '#1877F2',
      aliases: ['facebook', 'face book', 'fb'],
      search: (q) => 'https://www.facebook.com/search/top/?q=' + enc(q),
    },
    {
      id: 'INSTAGRAM', label: 'Instagram', homeUrl: 'https://www.instagram.com/', color: '#C13584',
      aliases: ['instagram', 'insta', 'ig'],
      // Instagram search needs a login; hashtag pages are the closest open equivalent.
      search: (q) => 'https://www.instagram.com/explore/tags/' +
        enc(String(q).replace(/[^\p{L}\p{N}]/gu, '').toLowerCase()) + '/',
    },
    {
      id: 'TIKTOK', label: 'TikTok', homeUrl: 'https://www.tiktok.com/', color: '#111111',
      aliases: ['tiktok', 'tik tok', 'tick tock', 'tic tac', 'tiktoks'],
      search: (q) => 'https://www.tiktok.com/search?q=' + enc(q),
    },
    {
      id: 'NINE_APPS', label: '9apps', homeUrl: 'https://www.9apps.com/', color: '#2FB344',
      aliases: ['9apps', '9 apps', 'nine apps', 'nineapps', '9 app', 'nine app'],
      search: (q) => 'https://www.google.com/search?q=site%3A9apps.com+' + enc(q),
    },
  ];
  const appById = (id) => APPS.find((a) => a.id === id) || null;

  /** The app whose website [url] belongs to, or null. */
  function appForUrl(url) {
    let host = '';
    try { host = new URL(url).hostname.replace(/^www\./, ''); } catch (e) { return null; }
    const hosts = {
      YOUTUBE: ['youtube.com', 'm.youtube.com', 'youtu.be'],
      FACEBOOK: ['facebook.com', 'm.facebook.com', 'web.facebook.com', 'mbasic.facebook.com', 'touch.facebook.com'],
      INSTAGRAM: ['instagram.com', 'm.instagram.com'],
      TIKTOK: ['tiktok.com', 'm.tiktok.com'],
      NINE_APPS: ['9apps.com'],
    };
    const id = Object.keys(hosts).find((k) => hosts[k].includes(host));
    return id ? appById(id) : null;
  }

  // ---------- web links ----------

  const WebLinks = {
    /** A normal web search (used outside the five apps). */
    searchUrl: (q) => 'https://www.google.com/search?q=' + enc(q),
    /**
     * An address like "claude.ai" opens directly; a name like "claude code" goes to
     * the top search result (DuckDuckGo's "I'm feeling ducky").
     */
    websiteUrl(target) {
      const t = String(target).trim();
      if (t.startsWith('http://') || t.startsWith('https://')) return t;
      if (looksLikeAddress(t)) return 'https://' + t;
      return 'https://duckduckgo.com/?q=' + enc('!ducky ' + t);
    },
    /** Other addresses that serve the same site, tried in order when one can't be reached. */
    alternativesFor(host) {
      const fb = ['www.facebook.com', 'web.facebook.com', 'm.facebook.com', 'mbasic.facebook.com', 'touch.facebook.com'];
      if (fb.includes(host) || host === 'facebook.com') return fb;
      if (['www.instagram.com', 'instagram.com', 'm.instagram.com'].includes(host)) return ['www.instagram.com', 'm.instagram.com'];
      if (['m.youtube.com', 'youtube.com', 'www.youtube.com'].includes(host)) return ['www.youtube.com', 'm.youtube.com'];
      if (['www.tiktok.com', 'tiktok.com', 'm.tiktok.com'].includes(host)) return ['www.tiktok.com', 'm.tiktok.com'];
      return [];
    },
  };

  // ---------- tap vocabulary ----------

  const next = ['next', 'next page', 'next video', 'go to next', 'forward', 'continue', '›', '»', '→', 'chevron right', 'arrow right'];
  const previous = ['previous', 'prev', 'previous page', 'previous video', 'go to previous', '‹', '«', '←', 'chevron left', 'arrow left'];
  const synonyms = {
    next, previous, prev: previous,
    back: ['back', 'go back', 'navigate up', 'previous', '‹', '←', 'arrow left'],
    close: ['close', 'dismiss', 'cancel', 'not now', 'no thanks', '×', '✕', 'x'],
    menu: ['menu', 'more options', 'options', 'more', '⋮', '☰', 'hamburger'],
    search: ['search', 'search button', 'magnifier', 'find'],
    settings: ['settings', 'preferences', 'gear', 'options'],
    more: ['more', 'show more', 'see more', 'read more', 'expand', 'load more'],
    play: ['play', 'play video', 'watch'],
    fullscreen: ['fullscreen', 'full screen', 'enter full screen'],
    'full screen': ['full screen', 'fullscreen', 'enter full screen'],
    post: ['post', 'publish', 'send', 'submit'],
    send: ['send', 'submit', 'post'],
    ok: ['ok', 'okay', 'accept', 'agree', 'confirm', 'yes'],
    accept: ['accept', 'accept all', 'agree', 'allow', 'ok'],
  };
  const TapVocabulary = {
    next, previous,
    labelsFor: (target) => distinct([target].concat(Object.prototype.hasOwnProperty.call(synonyms, target) ? synonyms[target] : [])),
  };

  // ---------- parser ----------

  const TAP = 'tap';
  const SEARCH = 'search';
  const TLDS = 'com|org|net|ai|io|co|uk|ug|ke|tz|ng|za|tv|me|app|dev|info|edu|gov|biz|us|ca|in';
  const itemWords = 'video|videos|post|posts|reel|reels|clip|short|shorts|one|item|story|result|results|link|song|movie|episode';

  const ordinals = {
    first: 1, '1st': 1, '1': 1, one: 1,
    second: 2, '2nd': 2, '2': 2, two: 2,
    third: 3, '3rd': 3, '3': 3, three: 3,
    fourth: 4, '4th': 4, '4': 4, four: 4,
    fifth: 5, '5th': 5, '5': 5, five: 5,
    sixth: 6, '6th': 6, '6': 6, six: 6,
    seventh: 7, '7th': 7, '7': 7, seven: 7,
    eighth: 8, '8th': 8, '8': 8, eight: 8,
    ninth: 9, '9th': 9, '9': 9, nine: 9,
    tenth: 10, '10th': 10, '10': 10, ten: 10,
  };
  const ordinalPattern = Object.keys(ordinals).sort((a, b) => b.length - a.length).join('|');

  /** Page buttons people ask for by name, with the labels sites use for them. */
  const pageButtons = [
    [/^(like|love|heart|double tap)( (it|this|that|the \w+|this \w+))?( (button|icon))?$/, ['like', 'love', 'heart']],
    [/^((open|show|read|see) )?(the )?comments?( (it|this|that|section|button|icon))?$/, ['comment', 'comments']],
    [/^share( (it|this|that|the \w+|this \w+|button|icon))?$/, ['share']],
    [/^follow( (him|her|them|this \w+|the \w+|creator|account|user|page|button))?$/, ['follow']],
    [/^subscribe( (to )?(him|her|them|this \w+|the \w+|channel|button))?$/, ['subscribe']],
    [/^(save|bookmark|favou?rite)( (it|this|that|the \w+|this \w+|button|icon))?$/, ['save', 'bookmark', 'favorite', 'favourite']],
    [/^dislike( (it|this|that|the \w+|this \w+|button|icon))?$/, ['dislike']],
    [/^(log ?in|sign ?in)( button)?$/, ['log in', 'sign in', 'login']],
    [/^(sign ?up|create account|register)( button)?$/, ['sign up', 'create account', 'register']],
    [/^(open )?(the )?menu( button| icon)?$/, ['menu', 'more', 'open menu']],
    [/^(open )?(my )?(profile|account)$/, ['profile', 'account', 'you']],
    [/^(open )?(my )?(notifications|inbox|messages)$/, ['notifications', 'inbox', 'messages']],
    [/^skip( the)?( ad| ads)?( button)?$/, ['skip ad', 'skip ads', 'skip']],
  ];

  const C = {
    Open: (app) => ({ type: 'Open', app }),
    Search: (query, app) => ({ type: 'Search', query, app: app || null }),
    OpenWebsite: (target) => ({ type: 'OpenWebsite', target, isAddress: looksLikeAddress(target) }),
    Tap: (spoken, labels) => ({ type: 'Tap', spoken, labels }),
    TapNth: (n) => ({ type: 'TapNth', n }),
    ScrollToNth: (n) => ({ type: 'ScrollToNth', n }),
    TypeText: (text, submit) => ({ type: 'TypeText', text, submit }),
    Ignored: (heard) => ({ type: 'Ignored', heard }),
    simple: (type) => ({ type }),
  };

  /**
   * Parses one input into the commands to run, in order. Several commands can be
   * joined: "tap open TikTok then search cats", or inside one tap,
   * "tap scroll to the second video and play it".
   */
  function parseAll(raw) {
    const text = normalize(raw);
    if (!(text.startsWith(TAP + ' ') || text.startsWith(SEARCH + ' '))) return [C.Ignored(raw)];
    const segments = text.split(new RegExp(` (?:and then|then|and|after that) (?=(?:${TAP}|${SEARCH}) )`));
    const out = [];
    for (const seg of segments) {
      if (seg.startsWith(SEARCH + ' ')) {
        const c = parseSearch(seg.slice(SEARCH.length + 1));
        if (c) out.push(c);
      } else if (seg.startsWith(TAP + ' ')) {
        out.push(...parseTapBody(seg.slice(TAP.length + 1)));
      }
    }
    return out.length ? out : [C.Ignored(raw)];
  }

  const parse = (raw) => parseAll(raw)[0];

  // ---------- search ----------

  function parseSearch(body) {
    let query = removePrefix(body.trim(), 'for ').trim();
    if (!query) return null;

    const site = websiteTarget(query);
    if (site) return site;

    // "search on tiktok for cats" / "search tiktok for cats"
    let m = /^(?:on |in )?(.+?) for (.+)$/.exec(query);
    if (m) {
      const app = exactApp(m[1]);
      if (app) return C.Search(m[2].trim(), app);
    }

    // "search cats on tiktok"
    let app = null;
    m = /^(.+) (?:on|in|using|with) (.+)$/.exec(query);
    if (m) {
      const candidate = exactApp(removeSuffix(m[2], ' app').trim());
      if (candidate) { app = candidate; query = m[1].trim(); }
    }
    return C.Search(query, app);
  }

  // ---------- tap ----------

  function parseTapBody(rawBody) {
    const body = cleanTapBody(rawBody);
    if (!body) return [];
    const typing = parseTyping(body);
    if (typing) return [typing];

    const parts = body.split(/ (?:and then|then|and|after that) /).map(cleanTapBody).filter((p) => p);
    if (parts.length > 1) {
      const actions = parts.map(knownAction);
      if (actions.every((a) => a)) return actions;
    }
    return [knownAction(body) || tapByName(body)];
  }

  function cleanTapBody(raw) {
    let t = removePrefix(removePrefix(raw.trim(), TAP + ' ').trim(), 'on ').trim();
    for (const suffix of [' on this page', ' on the page', ' on this screen', ' on the screen', ' here', ' for me']) {
      t = removeSuffix(t, suffix).trim();
    }
    return t;
  }

  function tapByName(body) {
    let target = removePrefix(body, 'the ').trim();
    for (const s of [' button', ' icon', ' link', ' tab']) target = removeSuffix(target, s);
    target = target.trim();
    const m = /^(?:open|go to|show|see) (.+)$/.exec(target);
    const labels = TapVocabulary.labelsFor(target).concat(m ? TapVocabulary.labelsFor(m[1]) : []);
    return C.Tap(target, distinct(labels));
  }

  function knownAction(raw) {
    const text = removePrefix(raw, 'the ').trim();
    const found = exactAction(text) || parseTyping(text) || parseScrollToNth(text) || parseNth(text) || parseFeedAndScroll(text);
    if (found) return found;
    const button = pageButtons.find(([re]) => re.test(text));
    if (button) return C.Tap(button[1][0], button[1]);
    const open = parseOpenApp(text);
    if (open) return open;
    const m = /^(?:click|press|select|hit|choose|pick)(?: on)? (.+)$/.exec(text);
    if (m) return knownAction(m[1]) || tapByName(m[1]);
    return null;
  }

  const exactActions = [
    ['Back', ['back', 'go back', 'back button', 'previous page', 'return', 'go to previous page']],
    ['Forward', ['forward', 'go forward']],
    ['Home', ['home', 'go home', 'home screen', 'home button', 'go to home', 'go to home screen', 'back to home',
      'go back home', 'close', 'close app', 'close it', 'close this', 'exit', 'exit app']],
    ['Reload', ['reload', 'refresh', 'reload page', 'refresh page', 'reload it', 'refresh it']],
    ['StopListening', ['stop listening', 'mic off', 'turn off mic', 'turn off the mic', 'voice off']],
    ['Help', ['help', 'commands', 'show commands']],
    ['Play', ['play', 'play it', 'play video', 'play the video', 'play this', 'play that', 'play this video',
      'resume', 'continue', 'unpause', 'start video', 'start it']],
    ['Pause', ['pause', 'pause it', 'pause video', 'pause the video', 'pause this', 'stop', 'stop it', 'stop video',
      'stop the video', 'stop playing']],
    ['Mute', ['mute', 'mute it', 'mute video', 'mute the video', 'sound off', 'volume off', 'turn off sound',
      'turn off the sound']],
    ['Unmute', ['unmute', 'unmute it', 'unmute video', 'sound on', 'volume on', 'turn on sound', 'turn on the sound']],
    ['ZoomIn', ['zoom in']],
    ['ZoomOut', ['zoom out']],
    ['PressEnter', ['enter', 'press enter', 'submit', 'send', 'search button']],
    ['Fullscreen', ['fullscreen', 'full screen', 'go full screen', 'go fullscreen', 'exit full screen', 'exit fullscreen']],
  ];

  function exactAction(text) {
    const hit = exactActions.find(([, words]) => words.includes(text));
    return hit ? C.simple(hit[0]) : null;
  }

  function parseTyping(text) {
    const m = /^(?:type|write|input|enter)(?: in)? (.+?)(?: (?:and|then) (search|enter|submit|send|go|post|press enter))?$/.exec(text);
    if (!m) return null;
    const typed = m[1].trim();
    if (!typed) return null;
    return C.TypeText(typed, !!m[2]);
  }

  function parseScrollToNth(text) {
    const m = new RegExp(`^(?:scroll|go|move|jump|swipe)(?: down| up)? to (?:the )?(?:number )?(${ordinalPattern})(?: (${itemWords}))?$`).exec(text);
    return m ? C.ScrollToNth(ordinals[m[1]]) : null;
  }

  function parseNth(text) {
    const m = new RegExp(
      `^(?:(?:open|play|watch|view|show|select|choose|pick)(?: on)? )?(?:the )?(?:number )?(${ordinalPattern})(?: (${itemWords}))?$`,
    ).exec(text);
    if (!m) return null;
    if (m[1] === 'one' && !m[2]) return null; // "tap one" alone is too vague
    return C.TapNth(ordinals[m[1]]);
  }

  function parseFeedAndScroll(text) {
    const words = new Set(text.split(' '));
    const items = itemWords.split('|');
    const mentionsItem = [...words].some((w) => items.includes(w));

    if (words.has('previous') || (words.has('last') && mentionsItem) || text === 'swipe down' || (words.has('before') && mentionsItem)) {
      return C.simple('PreviousItem');
    }
    if (words.has('next') || (words.has('skip') && mentionsItem) || (words.has('another') && mentionsItem) ||
      text === 'swipe up' || text === 'swipe') {
      return C.simple('NextItem');
    }
    const isScroll = words.has('scroll') || words.has('swipe') || ['down', 'up', 'page down', 'page up'].includes(text);
    if (!isScroll) return null;
    if (words.has('top') || words.has('beginning') || words.has('start')) return C.simple('ScrollTop');
    if (words.has('bottom') || words.has('end')) return C.simple('ScrollBottom');
    if (words.has('up')) return C.simple('ScrollUp');
    return C.simple('ScrollDown');
  }

  function parseOpenApp(text) {
    const app = exactApp(text);
    if (app) return C.Open(app);
    if (looksLikeAddress(text)) return C.OpenWebsite(text);
    const m = /^(?:open|launch|start|go to|switch to|show|visit|take me to) (.+)$/.exec(text);
    if (!m) return null;
    const rest = removeSuffix(m[1], ' app').trim();
    const named = exactApp(rest);
    if (named) return C.Open(named);
    return websiteTarget(rest);
  }

  const websiteSuffixes = [
    ' official website', ' official site', ' website', ' web site', ' webpage', ' web page', ' homepage', ' home page', ' site',
  ];

  function websiteTarget(text) {
    let t = removePrefix(removePrefix(removePrefix(text.trim(), 'the '), 'website '), 'site ').trim();
    if (looksLikeAddress(t)) return C.OpenWebsite(t);
    const suffix = websiteSuffixes.find((s) => t.endsWith(s));
    if (!suffix) return null;
    t = removePrefix(removeSuffix(removeSuffix(t, suffix).trim(), "'s"), 'the ').trim();
    if (!t) return null;
    const app = exactApp(t);
    if (app) return C.Open(app); // "search YouTube website" just opens YouTube
    return C.OpenWebsite(t);
  }

  /** "claude.ai", "www.bbc.co.uk/news", "https://example.com". */
  function looksLikeAddress(text) {
    return /^(https?:\/\/)?([a-z0-9-]+\.)+[a-z]{2,}(\/\S*)?$/.test(String(text).trim());
  }

  function exactApp(text) {
    const t = removePrefix(text.trim(), 'the ').trim();
    return APPS.find((app) => app.aliases.includes(t)) || null;
  }

  /** Finds the app the user named anywhere in [text], or null. Longest alias wins. */
  function appFromSpoken(text) {
    const n = normalize(text);
    if (!n) return null;
    const pairs = [];
    APPS.forEach((app) => app.aliases.forEach((alias) => pairs.push([alias, app])));
    pairs.sort((a, b) => b[0].length - a[0].length);
    const hit = pairs.find(([alias]) => n === alias || new RegExp(`(^|\\s)${escapeRegex(alias)}($|\\s)`).test(n));
    return hit ? hit[1] : null;
  }

  /**
   * Lowercase, strip punctuation and polite filler so "Hey Arrow, please tap like!"
   * becomes "tap like". "Tab" is accepted as a common mishearing of "tap".
   */
  function normalize(raw) {
    let t = String(raw || '').toLowerCase()
      .replace(/[^a-z0-9\s./:-]/g, ' ')
      .replace(/(?<![a-z0-9])[./:-]|[./:-](?![a-z0-9])/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    t = t.replace(new RegExp(`([a-z0-9-]+) dot (${TLDS})\\b`, 'g'), '$1.$2');
    t = t.split(' ').map((w) => (w.includes('.') && looksLikeAddress(w) ? w : w.replace(/[./:-]/g, ' ')))
      .join(' ').replace(/\s+/g, ' ').trim();
    const fillers = [
      'hey arrow', 'ok arrow', 'okay arrow', 'arrow', 'please', 'can you', 'could you', 'would you',
      'will you', 'i want to', 'i wanna', 'i would like to', 'now', 'just', 'kindly', 'hey', 'ok', 'okay',
    ];
    let changed = true;
    while (changed) {
      changed = false;
      for (const f of fillers) {
        if (t.startsWith(f + ' ')) { t = t.slice(f.length + 1).trim(); changed = true; }
      }
    }
    for (const suffix of [' please', ' now', ' right now']) t = removeSuffix(t, suffix).trim();
    t = t.replace(/^(tab|tapped|taps|tip) /, 'tap ');
    t = t.replace(/ (?:and|then) (tab|tapped|taps) /g, ' and tap ');
    t = t.replace(/^(searching|searched|search for) /, 'search ');
    return t;
  }

  const api = { APPS, appById, appForUrl, appFromSpoken, WebLinks, TapVocabulary, parse, parseAll, normalize, looksLikeAddress };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ArrowCommands = api;
})(typeof window !== 'undefined' ? window : globalThis);
