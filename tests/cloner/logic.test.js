// Tests for Arrow Cloner's brain and planner. Run: node --test tests/cloner
const test = require('node:test');
const assert = require('node:assert');
const L = require('../../app/src/main/assets/cloner/logic.js');

test('knows a website developer offline', () => {
  const p = L.professionProfile('website developer');
  assert.strictEqual(p.known, true);
  assert.strictEqual(p.matched, 'Website developer');
  assert.ok(p.tools.includes('claude'));
  assert.ok(p.accounts.some((a) => a.kind === 'payment'));
});

test('an unknown profession still gets a profile', () => {
  const p = L.professionProfile('beekeeper');
  assert.strictEqual(p.known, false);
  assert.strictEqual(p.name, 'Beekeeper');
  assert.ok(p.summary.length > 0);
  assert.ok(p.duties.length > 0);
});

test('web research replaces the summary and adds sources and requirements', () => {
  const p = L.addResearch(L.professionProfile('web developer'), {
    wikipedia: { title: 'Web developer', extract: 'A web developer is a programmer who develops websites.', url: 'https://en.wikipedia.org/wiki/Web_developer' },
    results: [{ title: 'How to become one', url: 'https://example.com', snippet: 'Most need a portfolio.' }],
    requirements: [{ snippet: 'Employers want a bachelor degree in computer science or a strong portfolio of sites.' }],
  });
  assert.match(p.summary, /programmer/);
  assert.strictEqual(p.sources.length, 2);
  assert.ok(p.requirements.length >= 1);
});

test('reads DuckDuckGo results and skips ads', () => {
  const html = `
    <a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad=1">Ad</a>
    <h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FWeb_developer&amp;rut=abc">Web <b>developer</b> - Wikipedia</a></h2>
    <a class="result__snippet" href="x">A <b>web developer</b> is a programmer &amp; designer.</a>`;
  const r = L.parseDuckDuckGo(html);
  assert.strictEqual(r.length, 1);
  assert.strictEqual(r[0].url, 'https://en.wikipedia.org/wiki/Web_developer');
  assert.strictEqual(r[0].title, 'Web developer - Wikipedia');
  assert.strictEqual(r[0].snippet, 'A web developer is a programmer & designer.');
});

test('reads videos from a YouTube search page', () => {
  const data = { contents: { list: [
    { videoRenderer: { videoId: 'abc', title: { runs: [{ text: 'Rich Dad Poor Dad summary' }] }, ownerText: { runs: [{ text: 'Books Channel' }] },
      detailedMetadataSnippets: [{ snippetText: { runs: [{ text: 'Assets put money in your pocket.' }] } }] } },
    { videoRenderer: { videoId: 'abc', title: { runs: [{ text: 'duplicate' }] } } },
    { compactVideoRenderer: { videoId: 'def', title: { simpleText: 'Financial literacy 101' } } },
  ] } };
  const html = '<script>var ytInitialData = ' + JSON.stringify(data) + ';</script>';
  const v = L.parseYouTube(html);
  assert.strictEqual(v.length, 2);
  assert.strictEqual(v[0].title, 'Rich Dad Poor Dad summary');
  assert.strictEqual(v[0].description, 'Assets put money in your pocket.');
  assert.strictEqual(v[0].channel, 'Books Channel');
  assert.strictEqual(v[1].id, 'def');
  assert.deepStrictEqual(L.parseYouTube('<html>nothing</html>'), []);
});

test('turns HTML into readable text', () => {
  const r = L.htmlToText('<html><title>Hi &amp; bye</title><script>x()</script><body><h1>Title</h1><p>Hello <b>world</b></p><nav>menu</nav></body></html>');
  assert.strictEqual(r.title, 'Hi & bye');
  assert.match(r.text, /Hello world/);
  assert.doesNotMatch(r.text, /x\(\)|menu/);
});

test('page structure lists headings', () => {
  const s = L.pageStructure('<h1>Bakery</h1><nav><a>Home</a></nav><h2>Our cakes</h2><form></form>');
  assert.ok(s.includes('H1: Bakery'));
  assert.ok(s.includes('H2: Our cakes'));
  assert.ok(s.some((l) => l.startsWith('nav')));
});

test('understands the YouTube learning order', () => {
  const l = L.parseLearn('go to YouTube search rich dad poor dad by Robert Kiyosaki and learn about financial literacy');
  assert.strictEqual(l.source, 'youtube');
  assert.strictEqual(l.query, 'rich dad poor dad by robert kiyosaki');
  assert.strictEqual(l.topic, 'financial literacy');
  assert.match(L.sourceSearchUrl(l.source, l.query), /^https:\/\/m\.youtube\.com\/results\?search_query=rich%20dad/);
});

test('a plain learning order goes to the web', () => {
  const l = L.parseLearn('learn about search engine optimisation');
  assert.strictEqual(l.source, 'web');
  assert.strictEqual(l.topic, 'search engine optimisation');
  assert.strictEqual(l.query, 'search engine optimisation');
});

test('the brain always knows Arrow Os and survives bad data', () => {
  const b = L.loadBrain('not json');
  assert.strictEqual(b.knowledge[0].id, 'arrow-os-manual');
  const saved = JSON.stringify({ worker: { profession: 'Photographer' }, knowledge: [{ id: 'arrow-os-manual', text: 'old' }, { id: 'x', title: 't', text: 'y' }] });
  const b2 = L.loadBrain(saved);
  assert.strictEqual(b2.worker.profession, 'Photographer');
  assert.strictEqual(b2.knowledge.length, 2);
  assert.strictEqual(b2.knowledge[0].text, L.ARROW_OS_MANUAL);
  assert.strictEqual(L.forget(b2, 'arrow-os-manual'), false);
});

test('learning the same thing twice keeps one copy; forget removes it', () => {
  const b = L.newBrain();
  L.learn(b, { kind: 'file', title: 'prices.txt', text: 'one', source: 'upload' });
  const k = L.learn(b, { kind: 'file', title: 'prices.txt', text: 'two', source: 'upload' });
  assert.strictEqual(b.knowledge.filter((x) => x.title === 'prices.txt').length, 1);
  assert.strictEqual(L.forget(b, k.id), true);
  assert.strictEqual(b.knowledge.length, 1);
});

test('recall finds the teacher\'s notes for a task', () => {
  const b = L.newBrain();
  L.learn(b, { kind: 'note', title: 'My website style', text: 'Every website I make uses a dark blue header. Always put the WhatsApp button on the website.' });
  L.learn(b, { kind: 'web', title: 'Cooking', text: 'Boil the rice for twenty minutes before serving it.' });
  const r = L.recall(b, 'make a website for Sarah');
  assert.strictEqual(r.length, 1);
  assert.strictEqual(r[0].item.title, 'My website style');
  assert.ok(r[0].lines.length >= 1);
});

test('lesson steps drop pages opened by a command', () => {
  const steps = L.lessonSteps([
    { kind: 'cmd', text: 'tap open whatsapp', at: 1000 },
    { kind: 'open', url: 'https://web.whatsapp.com/', at: 2000 },
    { kind: 'open', url: 'https://claude.ai/code', title: 'Claude', at: 20000 },
    { kind: 'open', url: 'https://claude.ai/code', at: 21000 },
    { kind: 'cmd', text: 'tap type hello and send', at: 30000 },
  ]);
  assert.deepStrictEqual(steps, [
    { type: 'cmd', text: 'tap open whatsapp' },
    { type: 'open', url: 'https://claude.ai/code', title: 'Claude' },
    { type: 'cmd', text: 'tap type hello and send' },
  ]);
});

test('Mj\'s website example: logins and payments first, then the work', () => {
  const b = L.newBrain();
  b.worker.profession = 'Website developer';
  const p = L.plan("go to whatsapp use the images and videos Sarah has sent and make a website in Claude code", b);
  assert.strictEqual(p.intent, 'website');
  assert.match(p.say, /^I'm on it/);
  const ids = p.needs.map((n) => n.id);
  for (const id of ['login-whatsapp', 'login-claude', 'pay-claude', 'pay-domain', 'login-hosting', 'login-google']) assert.ok(ids.includes(id), id);
  assert.strictEqual(p.steps[0].url, 'https://web.whatsapp.com/');
  assert.match(p.steps[1].text, /Sarah/);
  const typed = p.steps.find((s) => s.type === 'type');
  assert.match(typed.text, /Sarah/);
  assert.match(typed.text, /website developer/);
  assert.ok(p.steps.some((s) => s.url === 'https://app.netlify.com/drop'));
  assert.ok(p.steps.some((s) => /business\.google\.com/.test(s.url || '')));
  assert.strictEqual(p.steps[p.steps.length - 1].text, 'Done.');
});

test('a recorded lesson is used when the task matches it', () => {
  const b = L.newBrain();
  b.lessons.push({ id: 'l1', title: 'post the daily menu', steps: [{ type: 'cmd', text: 'tap open facebook' }] });
  const p = L.plan('post the daily menu', b);
  assert.strictEqual(p.intent, 'lesson');
  assert.strictEqual(p.lesson, 'l1');
  assert.ok(p.steps.some((s) => s.text === 'tap open facebook'));
});

test('inspiration shapes the Claude prompt', () => {
  const b = L.newBrain();
  const p = L.plan('build a website for my bakery', b, { inspiration: { url: 'https://example.com', structure: ['H1: Fresh bread', 'nav: Home Menu'] } });
  const typed = p.steps.find((s) => s.type === 'type');
  assert.match(typed.text, /example\.com/);
  assert.match(typed.text, /Fresh bread/);
});

test('an unknown job asks to be taught', () => {
  const p = L.plan('feed the chickens', L.newBrain());
  assert.strictEqual(p.intent, 'general');
  assert.ok(p.steps.some((s) => s.type === 'you' && /record/i.test(s.text)));
});

test('finds who sent the files', () => {
  assert.strictEqual(L.senderOf('use the images Sarah has sent'), 'Sarah');
  assert.strictEqual(L.senderOf('use the photos from John'), 'John');
  assert.strictEqual(L.senderOf('make a website'), '');
});
