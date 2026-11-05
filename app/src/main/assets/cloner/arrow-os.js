/*
 * Arrow Os bridge for Arrow Cloner.
 *
 * Arrow Cloner does its work through Arrow Os. It speaks Arrow Os's own language:
 * every step is one command that starts with "tap" or "search", or "wait N" to give a
 * slow page time. A finished task becomes a link:
 *
 *     arrowos://run?name=<task name>&steps=<one step per line>
 *
 * Opening that link on a phone with Arrow Os (or tapping it inside Arrow Os) shows the
 * steps and runs them when the user taps Run. See ../ARROW_OS.md for the full manual.
 *
 * Works in a browser (window.ArrowOs) and in Node (module.exports). No libraries,
 * no keys, nothing to pay for.
 */
(function (root) {
  'use strict';

  var MAX_STEPS = 60;
  var MAX_WAIT = 60;

  /** Apps Arrow Os knows by name. Any other website works with "tap open <address>". */
  var APPS = {
    youtube: ['youtube', 'you tube', 'yt'],
    facebook: ['facebook', 'fb'],
    instagram: ['instagram', 'insta', 'ig'],
    tiktok: ['tiktok', 'tik tok'],
    '9apps': ['9apps', '9 apps', 'nine apps'],
    gmail: ['gmail', 'email', 'my email', 'mail', 'inbox'],
    whatsapp: ['whatsapp', 'whats app', 'wa'],
    'google drive': ['google drive', 'drive'],
    'google maps': ['google maps', 'maps', 'map'],
    'arrow files': ['arrow files', 'my files', 'files', 'downloads']
  };

  /** Steps Arrow Os refuses inside a task (they change Arrow Os itself). */
  var REFUSED = [
    /^tap (stop listening|mic off|turn off (the )?mic|voice off)$/,
    /^tap (clear|delete) (previous|history|my history|tab history|previous tabs)$/,
    /^tap (help|commands|show commands)$/,
    /^tap (teach|start teaching|start recording|record|new skill|watch me|learn this)( .*)?$/,
    /^tap (stop teaching|done teaching|finish teaching|stop recording|save skill|save the skill|save this skill)( .*)?$/,
    /^tap (do|run|repeat|clone) .+$/,
    /^tap (arrow cloner|cloner|skills|my skills|cloner skills|show skills)$/
  ];

  var FILLERS = ['hey arrow', 'ok arrow', 'okay arrow', 'arrow', 'please', 'can you', 'could you', 'just', 'kindly', 'hey', 'ok', 'okay'];

  /** Lowercase, tidy, drop polite filler: "Hey Arrow, please tap like!" -> "tap like". */
  function normalize(step) {
    var t = String(step || '').toLowerCase()
      .replace(/[^a-z0-9\s./:+#-]/g, ' ')
      .replace(/\s+/g, ' ').trim();
    var changed = true;
    while (changed) {
      changed = false;
      for (var i = 0; i < FILLERS.length; i++) {
        if (t.indexOf(FILLERS[i] + ' ') === 0) { t = t.slice(FILLERS[i].length + 1).trim(); changed = true; }
      }
    }
    return t.replace(/^(tab|tapped|taps) /, 'tap ').replace(/^(search for|searching) /, 'search ');
  }

  /**
   * Checks one step the way Arrow Os will.
   * Returns { ok, step, kind: 'tap'|'search'|'wait', reason }.
   */
  function check(step) {
    var raw = String(step || '').trim();
    var t = normalize(raw);
    var wait = /^(?:wait|sleep|pause for)(?: for)? (\d{1,3})(?: ?(?:s|sec|secs|second|seconds))?$/.exec(t);
    if (wait) {
      var s = Math.min(Math.max(parseInt(wait[1], 10), 1), MAX_WAIT);
      return { ok: true, step: 'wait ' + s, kind: 'wait' };
    }
    if (!/^(tap|search) \S/.test(t)) {
      return { ok: false, step: raw, reason: 'must start with "tap" or "search"' };
    }
    for (var i = 0; i < REFUSED.length; i++) {
      if (REFUSED[i].test(t)) return { ok: false, step: raw, reason: 'Arrow Os does not allow this inside a task' };
    }
    return { ok: true, step: raw, kind: t.indexOf('tap ') === 0 ? 'tap' : 'search' };
  }

  function splitSteps(text) {
    return String(text || '').split(/\n|;/)
      .map(function (s) { return s.trim(); })
      .filter(function (s) { return s && s.charAt(0) !== '#'; })
      .slice(0, MAX_STEPS);
  }

  /** The link that hands a task to Arrow Os. */
  function link(name, steps) {
    return 'arrowos://run?name=' + encodeURIComponent(name || 'Arrow Cloner task') +
      '&steps=' + encodeURIComponent(steps.join('\n'));
  }

  /**
   * Builds a task. [steps] is an array or text with one step per line.
   * Returns { name, steps, problems, ok, link }.
   */
  function task(name, steps) {
    var list = Array.isArray(steps) ? steps.slice(0, MAX_STEPS) : splitSteps(steps);
    var good = [];
    var problems = [];
    list.forEach(function (s) {
      var c = check(s);
      if (c.ok) good.push(c.step); else problems.push(c);
    });
    return { name: name || 'Arrow Cloner task', steps: good, problems: problems, ok: good.length > 0, link: link(name, good) };
  }

  /** Reads an arrowos://run link back into { name, steps }. */
  function read(href) {
    var m = /^arrowos:\/\/run\?(.*)$/i.exec(String(href || '').trim());
    if (!m) return null;
    var out = { name: 'Arrow Cloner task', steps: [] };
    m[1].split('&').forEach(function (pair) {
      var i = pair.indexOf('=');
      if (i < 0) return;
      var key = decodeURIComponent(pair.slice(0, i)).toLowerCase();
      var value = decodeURIComponent(pair.slice(i + 1));
      if (key === 'name') out.name = value;
      if (key === 'steps') out.steps = splitSteps(value);
    });
    return out;
  }

  // ---------- Planner: plain words -> Arrow Os steps ----------

  function appIn(text) {
    var found = null;
    Object.keys(APPS).forEach(function (app) {
      APPS[app].forEach(function (alias) {
        if (!found && new RegExp('(^| )' + alias.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '( |$)').test(text)) found = app;
      });
    });
    return found;
  }

  function stripApp(text) {
    return text.replace(/ (?:on|in|from|using|at) (?:the )?[a-z0-9 ]+?(?: app)?$/, function (m) {
      return appIn(m) ? '' : m;
    }).trim();
  }

  var ORDINALS = ['', 'first', 'second', 'third', 'fourth', 'fifth', 'sixth', 'seventh', 'eighth', 'ninth', 'tenth'];

  /**
   * Recipes Arrow Cloner already knows. Each turns one plain-words goal into steps.
   * Add new ones at the top; the first that matches wins.
   */
  var RECIPES = [
    { // "like the first 3 videos about cats on tiktok"
      re: /^like (?:the )?(?:first |top )?(\d+) (?:videos?|posts?|reels?)(?: (?:about|of|on) (.+?))?$/,
      make: function (m, app) {
        var n = Math.min(parseInt(m[1], 10), 10);
        var steps = [];
        if (m[2]) steps.push('search ' + stripApp(m[2]) + (app ? ' on ' + app : ''));
        else if (app) steps.push('tap open ' + app);
        steps.push('wait 3', 'tap play the first video');
        for (var i = 0; i < n; i++) {
          steps.push('wait 2', 'tap like this video');
          if (i < n - 1) steps.push('tap next video');
        }
        return steps;
      }
    },
    { // "watch tom and jerry" / "play despacito on youtube"
      re: /^(?:watch|play|listen to|show me) (.+)$/,
      make: function (m, app) {
        var what = stripApp(m[1]);
        var where = app || 'youtube';
        return ['search ' + what + ' on ' + where, 'wait 3', 'tap play the first video'];
      }
    },
    { // "comment nice work on this video"
      re: /^comment (.+?)(?: on (?:this|the) (?:video|post|reel))?$/,
      make: function (m) { return ['tap comment', 'wait 2', 'tap type ' + m[1] + ' and send']; }
    },
    { // "follow mrbeast on instagram"
      re: /^(follow|subscribe to) (.+)$/,
      make: function (m, app) {
        var who = stripApp(m[2]);
        var where = app || (m[1] === 'follow' ? 'instagram' : 'youtube');
        return ['search ' + who + ' on ' + where, 'wait 3', 'tap open the first result', 'wait 3',
          m[1] === 'follow' ? 'tap follow' : 'tap subscribe'];
      }
    },
    { // "message john hello on whatsapp" / "send hi to mum on whatsapp"
      re: /^(?:message|text|send) (?:(.+?) (?:saying|that) (.+)|(.+?) to (.+))$/,
      make: function (m, app) {
        var who = m[1] || stripApp(m[4]);
        var words = m[2] ? stripApp(m[2]) : m[3];
        return ['tap open ' + (app || 'whatsapp'), 'wait 5', 'tap ' + who, 'wait 2', 'tap type ' + words + ' and send'];
      }
    },
    { // "check my email"
      re: /^(?:check|read|open) (?:my )?(?:email|emails|gmail|inbox|mail)$/,
      make: function () { return ['tap open gmail', 'wait 4', 'tap the first one']; }
    },
    { // "scroll tiktok for 5 videos" / "next 5 videos"
      re: /^(?:scroll|browse|swipe)(?: through)?(?: [a-z ]+?)?(?: for)? (\d+) (?:videos?|posts?|reels?)$/,
      make: function (m, app) {
        var steps = app ? ['tap open ' + app, 'wait 4'] : [];
        for (var i = 0; i < Math.min(parseInt(m[1], 10), 20); i++) steps.push('tap next video', 'wait 5');
        return steps;
      }
    },
    { // "open the 2nd result" / "play the third video"
      re: /^(?:open|tap|play|pick) (?:the )?(\d+|first|second|third|fourth|fifth)(?:st|nd|rd|th)? (?:video|result|post|one)$/,
      make: function (m) {
        var n = /^\d+$/.test(m[1]) ? ORDINALS[parseInt(m[1], 10)] || m[1] : m[1];
        return ['tap play the ' + n + ' video'];
      }
    },
    { // "type hello world and send"
      re: /^(?:type|write) (.+)$/,
      make: function (m) { return ['tap type ' + m[1]]; }
    },
    { // "go home", "go back"
      re: /^(?:go )?(home|back)$/,
      make: function (m) { return ['tap ' + m[1]]; }
    },
    { // "open canva" / "go to bbc.com"
      re: /^(?:open|go to|visit|launch|start) (.+)$/,
      make: function (m) { return ['tap open ' + m[1]]; }
    },
    { // "search cats on tiktok" / "find a cheap phone"
      re: /^(?:search|find|look up|look for|google)(?: for)? (.+)$/,
      make: function (m, app) { return ['search ' + stripApp(m[1]) + (app ? ' on ' + app : '')]; }
    }
  ];

  /**
   * Turns a goal in plain words into Arrow Os steps. Goals can be chained with "then"
   * or a comma: "open tiktok, then like the first 3 videos about cats".
   * Lines that are already Arrow Os commands are kept as they are.
   * Returns { steps, unknown } where unknown lists the parts no recipe matched
   * (they are turned into a web search so the task still does something useful).
   */
  function plan(goal) {
    var steps = [];
    var unknown = [];
    var lastApp = null;
    String(goal || '').split(/\n|,? then |, and then |, |\. /i).forEach(function (part) {
      var text = normalize(part).replace(/^(?:and|then|also|i want to|i want|go and) /, '').trim();
      if (!text) return;
      if (/^(tap|search) /.test(text) || /^wait \d/.test(text)) { steps.push(text); return; }
      var app = appIn(text) || null;
      var target = app ? text : text;
      for (var i = 0; i < RECIPES.length; i++) {
        var m = RECIPES[i].re.exec(target);
        if (m) {
          var made = RECIPES[i].make(m, app || lastApp);
          steps = steps.concat(made);
          if (app) lastApp = app;
          return;
        }
      }
      unknown.push(part.trim());
      steps.push('search ' + text);
    });
    return { steps: steps.slice(0, MAX_STEPS), unknown: unknown };
  }

  var ArrowOs = {
    MAX_STEPS: MAX_STEPS,
    APPS: APPS,
    normalize: normalize,
    check: check,
    task: task,
    link: link,
    read: read,
    plan: plan,
    recipes: RECIPES
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = ArrowOs;
  else root.ArrowOs = ArrowOs;
})(typeof window !== 'undefined' ? window : this);
