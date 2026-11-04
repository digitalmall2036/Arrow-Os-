// Same cases as the Android app's CommandParserTest.kt, so both versions agree.
const test = require('node:test');
const assert = require('node:assert/strict');
const A = require('../src/commands.js');

const parse = (s) => A.parse(s);
const all = (s) => A.parseAll(s);
const app = (id) => A.appById(id);
const S = (query, appId) => ({ type: 'Search', query, app: appId ? app(appId) : null });
const W = (target) => ({ type: 'OpenWebsite', target, isAddress: A.looksLikeAddress(target) });
const T = (spoken, labels) => ({ type: 'Tap', spoken, labels });
const simple = (type) => ({ type });

test('only tap and search do anything', () => {
  for (const s of ['Jeff Bezos', 'open YouTube', 'scroll down', 'next video', 'like this video', '', 'tap', 'search']) {
    assert.equal(parse(s).type, 'Ignored', s);
  }
});

test('search', () => {
  assert.deepEqual(parse('search Tom and Jerry'), S('tom and jerry'));
  assert.deepEqual(parse('Search Jeff Bezos'), S('jeff bezos'));
  assert.deepEqual(parse('search cats on TikTok'), S('cats', 'TIKTOK'));
  assert.deepEqual(parse('search tiktok for dogs'), S('dogs', 'TIKTOK'));
  assert.deepEqual(parse('search for funny cats'), S('funny cats'));
  assert.deepEqual(parse('search food in new york'), S('food in new york'));
  assert.deepEqual(parse('search the next episode'), S('the next episode'));
});

test('tap plays the nth video', () => {
  assert.deepEqual(parse('tap play the second video on this page'), { type: 'TapNth', n: 2 });
  assert.deepEqual(parse('tap open the first video'), { type: 'TapNth', n: 1 });
  assert.deepEqual(parse('tap the third result'), { type: 'TapNth', n: 3 });
  assert.deepEqual(parse('tap second video'), { type: 'TapNth', n: 2 });
});

test('tap scroll to video and play it', () => {
  const want = (n) => [{ type: 'ScrollToNth', n }, simple('Play')];
  assert.deepEqual(all('tap scroll to second video and play it'), want(2));
  assert.deepEqual(all('tap scroll to the third video then play it'), want(3));
  assert.deepEqual(all('tap scroll to the second video and tap play it'), want(2));
});

test('tap page buttons', () => {
  const like = ['like', 'love', 'heart'];
  assert.deepEqual(parse('tap like this video').labels, like);
  assert.deepEqual(parse('tap the like icon').labels, like);
  assert.deepEqual(parse('tap like').labels, like);
  for (const w of ['comment', 'share', 'follow', 'subscribe']) assert.equal(parse('tap ' + w).spoken, w);
  assert.equal(parse('tap skip ad').spoken, 'skip ad');
});

test('tap anything by name', () => {
  assert.deepEqual(parse('tap Shorts'), T('shorts', ['shorts']));
  const settings = parse('tap settings').labels;
  assert.equal(settings[0], 'settings');
  assert.ok(settings.includes('preferences') && settings.includes('gear'));
  assert.deepEqual(parse('tap on Following'), T('following', ['following']));
  assert.deepEqual(parse('tap Tom and Jerry'), T('tom and jerry', ['tom and jerry']));
});

test('tap navigation', () => {
  assert.deepEqual(parse('tap open YouTube'), { type: 'Open', app: app('YOUTUBE') });
  assert.deepEqual(parse('tap TikTok'), { type: 'Open', app: app('TIKTOK') });
  assert.deepEqual(parse('tap open nine apps'), { type: 'Open', app: app('NINE_APPS') });
  assert.deepEqual(parse('tap home'), simple('Home'));
  assert.deepEqual(parse('tap back'), simple('Back'));
  assert.deepEqual(parse('tap go back'), simple('Back'));
  assert.deepEqual(parse('tap refresh'), simple('Reload'));
});

test('tap feed and scroll', () => {
  assert.deepEqual(parse('tap next video'), simple('NextItem'));
  assert.deepEqual(parse('tap scroll to the next video'), simple('NextItem'));
  assert.deepEqual(parse('tap previous video'), simple('PreviousItem'));
  assert.deepEqual(parse('tap scroll down'), simple('ScrollDown'));
  assert.deepEqual(parse('tap scroll up'), simple('ScrollUp'));
  assert.deepEqual(parse('tap scroll to the top'), simple('ScrollTop'));
});

test('tap video controls', () => {
  assert.deepEqual(parse('tap play'), simple('Play'));
  assert.deepEqual(parse('tap pause'), simple('Pause'));
  assert.deepEqual(parse('tap mute'), simple('Mute'));
  assert.deepEqual(parse('tap unmute'), simple('Unmute'));
  assert.deepEqual(parse('tap full screen'), simple('Fullscreen'));
});

test('tap typing', () => {
  assert.deepEqual(parse('tap type happy birthday and send'), { type: 'TypeText', text: 'happy birthday', submit: true });
  assert.deepEqual(parse('tap type hello'), { type: 'TypeText', text: 'hello', submit: false });
  assert.deepEqual(parse('tap enter'), simple('PressEnter'));
});

test('chained tap and search', () => {
  assert.deepEqual(all('tap open TikTok then search cats'), [{ type: 'Open', app: app('TIKTOK') }, S('cats')]);
  assert.deepEqual(all('search cats and tap the first video'), [S('cats'), { type: 'TapNth', n: 1 }]);
});

test('filler and mishearings', () => {
  assert.deepEqual(parse('Hey Arrow, please tap open YouTube!'), { type: 'Open', app: app('YOUTUBE') });
  assert.deepEqual(parse('tab pause'), simple('Pause'));
  assert.deepEqual(parse('tap help'), simple('Help'));
  assert.deepEqual(parse('tap stop listening'), simple('StopListening'));
});

test('websites', () => {
  assert.deepEqual(parse('search Claude code website'), W('claude code'));
  assert.deepEqual(parse('search the BBC website'), W('bbc'));
  assert.deepEqual(parse('search claude.ai'), W('claude.ai'));
  assert.deepEqual(parse('search claude dot ai'), W('claude.ai'));
  assert.deepEqual(parse('tap open bbc.co.uk/news'), W('bbc.co.uk/news'));
  assert.deepEqual(parse('tap go to jumia.ug'), W('jumia.ug'));
  assert.deepEqual(parse('tap open New Vision website'), W('new vision'));
  assert.deepEqual(parse('search YouTube website'), { type: 'Open', app: app('YOUTUBE') });
  assert.equal(parse('search claude.ai').isAddress, true);
  assert.equal(parse('search Claude code website').isAddress, false);
  assert.deepEqual(parse('search how to cook rice'), S('how to cook rice'));
});

test('tap open something on the page', () => {
  const tap = parse('tap open settings');
  assert.equal(tap.labels[0], 'open settings');
  assert.ok(tap.labels.includes('settings'));
  assert.deepEqual(parse('tap sign up'), T('sign up', ['sign up', 'create account', 'register']));
});

test('web links', () => {
  assert.equal(A.WebLinks.websiteUrl('claude.ai'), 'https://claude.ai');
  assert.equal(A.WebLinks.websiteUrl('claude code'), 'https://duckduckgo.com/?q=%21ducky+claude+code');
  assert.equal(A.WebLinks.searchUrl('jeff bezos'), 'https://www.google.com/search?q=jeff+bezos');
  assert.ok(A.WebLinks.alternativesFor('www.facebook.com').includes('web.facebook.com'));
  assert.ok(A.WebLinks.alternativesFor('m.facebook.com').includes('mbasic.facebook.com'));
});

test('search urls encode the query', () => {
  assert.equal(app('YOUTUBE').search('funny cats'), 'https://www.youtube.com/results?search_query=funny+cats');
  assert.equal(app('TIKTOK').search('a&b'), 'https://www.tiktok.com/search?q=a%26b');
  assert.equal(app('INSTAGRAM').search('Funny Cats'), 'https://www.instagram.com/explore/tags/funnycats/');
  assert.equal(A.appForUrl('https://www.tiktok.com/@someone').id, 'TIKTOK');
  assert.equal(A.appForUrl('https://example.com/'), null);
});
