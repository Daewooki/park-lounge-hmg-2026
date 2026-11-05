/* Run the real UI script against its HTML with a small DOM/event harness.
 * Tests state, focus and bridge calls, not CSS layout, WebGL or Android lifecycle. */
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const web = path.join(__dirname, '../app/src/main/assets/web');

class Events {
  constructor() { this.listeners = new Map(); }
  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }
  dispatchEvent(event) {
    event.target ??= this;
    event.preventDefault ??= () => { event.defaultPrevented = true; };
    for (const listener of this.listeners.get(event.type) || []) listener(event);
    return !event.defaultPrevented;
  }
}
class Element extends Events {
  constructor(tag, document) {
    super(); this.tag = tag; this.ownerDocument = document; this.attrs = {};
    this.children = []; this.parentElement = null; this._text = '';
    this.hidden = false; this.inert = false; this.disabled = false; this.open = false;
    this.style = {setProperty(key, value) { this[key] = value; }};
    this.dataset = new Proxy({}, {
      get: (_, key) => this.attrs['data-' + key.replace(/[A-Z]/g, c => '-' + c.toLowerCase())],
      set: (_, key, value) => { this.attrs['data-' + key.replace(/[A-Z]/g, c => '-' + c.toLowerCase())] = String(value); return true; }
    });
    this.classList = {
      contains: value => this.className.split(/\s+/).includes(value),
      toggle: (value, force) => {
        const classes = new Set(this.className.split(/\s+/).filter(Boolean));
        const on = force ?? !classes.has(value);
        if (on) classes.add(value); else classes.delete(value);
        this.className = [...classes].join(' '); return on;
      }
    };
  }
  get id() { return this.attrs.id || ''; }
  set id(value) { this.attrs.id = value; }
  get className() { return this.attrs.class || ''; }
  set className(value) { this.attrs.class = value; }
  get textContent() { return this._text + this.children.map(c => c.textContent).join(''); }
  set textContent(value) { this.replaceChildren(); this._text = String(value); }
  setAttribute(key, value) {
    this.attrs[key] = String(value);
    if (['hidden', 'inert', 'disabled'].includes(key)) this[key] = true;
  }
  getAttribute(key) { return this.attrs[key] ?? null; }
  appendChild(child) { child.parentElement = this; this.children.push(child); return child; }
  append(...children) { children.forEach(child => this.appendChild(child)); }
  replaceChildren() { this.children.forEach(c => { c.parentElement = null; }); this.children = []; this._text = ''; }
  contains(node) { return node === this || this.children.some(child => child.contains(node)); }
  matches(selector) {
    if (selector.endsWith(':last-child')) {
      return this.parentElement?.children.at(-1) === this && this.matches(selector.replace(':last-child', ''));
    }
    if (selector.startsWith('#')) return this.id === selector.slice(1);
    if (selector.startsWith('.')) return this.classList.contains(selector.slice(1));
    if (selector.startsWith('[')) return Object.hasOwn(this.attrs, selector.slice(1, -1));
    return this.tag === selector;
  }
  querySelectorAll(selector) {
    return this.children.flatMap(child => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]);
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  focus() {
    for (let node = this; node; node = node.parentElement) if (node.hidden || node.inert) return;
    if (!this.disabled) this.ownerDocument.activeElement = this;
  }
  showModal() { this.open = true; }
  close() { this.open = false; }
}
function documentFromHtml(html) {
  const document = new Events(), root = new Element('document', document), stack = [root];
  const voidTags = new Set(['meta', 'link', 'img', 'input', 'br', 'hr']);
  // Enough HTML parsing for the real static markup; script contents are external.
  for (const token of html.match(/<!--[\s\S]*?-->|<![^>]*>|<\/?[\w:-]+\b[^>]*>|[^<]+/g)) {
    if (token.startsWith('<!')) continue;
    if (token.startsWith('</')) { stack.pop(); continue; }
    if (!token.startsWith('<')) { stack.at(-1)._text += token; continue; }
    const tag = token.match(/^<([\w:-]+)/)[1], element = new Element(tag, document);
    const attrs = token.slice(tag.length + 1, token.length - 1).replace(/\/$/, '');
    for (const match of attrs.matchAll(/([^\s=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s]+)))?/g)) {
      element.setAttribute(match[1], match[2] ?? match[3] ?? match[4] ?? '');
    }
    stack.at(-1).appendChild(element);
    if (!voidTags.has(tag) && !token.endsWith('/>')) stack.push(element);
  }
  document.querySelectorAll = selector => root.querySelectorAll(selector);
  document.querySelector = selector => root.querySelector(selector);
  document.getElementById = id => root.querySelector('#' + id);
  document.createElement = tag => new Element(tag, document);
  document.createElementNS = (_, tag) => new Element(tag, document);
  document.documentElement = root.querySelector('html'); document.hidden = false;
  document.activeElement = root.querySelector('body');
  return document;
}
function harness() {
  const document = documentFromHtml(fs.readFileSync(path.join(web, 'index.html'), 'utf8'));
  const media = new Events(); media.matches = true;
  const motionMedia = new Events(); motionMedia.matches = false;
  const window = new Events(), calls = [], timers = new Map(); let timerId = 0;
  window.matchMedia = query => query.includes('prefers-reduced-motion')?motionMedia:media;
  window.Android = Object.fromEntries(['ready', 'play', 'pause', 'selectScene', 'setLayerVolume', 'setTimer', 'savePreferences', 'openProduct', 'reportContent']
    .map(method => [method, (...args) => { calls.push({method, args}); }]));
  const context = vm.createContext({window, document, location: {search: ''}, URLSearchParams,
    CustomEvent: class { constructor(type, options) { this.type = type; Object.assign(this, options); } },
    setTimeout(callback) { timers.set(++timerId, callback); return timerId; }, clearTimeout(id) { timers.delete(id); }});
  vm.runInContext(fs.readFileSync(path.join(web, 'app.js'), 'utf8'), context, {filename: 'app.js'});
  const get = id => { const element = document.getElementById(id); assert.ok(element, 'Missing UI element ' + id); return element; };
  const click = id => { const element = get(id); element.focus(); element.dispatchEvent({type: 'click'}); };
  const nativeState = {parked: true, sceneId: 'rain', hasSelectedScene:true, language: 'ko', volumes: [.25, .6, .15],
    timerMinutes: 15, remainingSeconds: 811, audioPlaying: true, preparing: false, error: ''};
  window.loadCatalog({scenes: [{id: 'rain', available: true, title: '비 오는 숲', titleEn: 'Rain shelter',
    layers: ['rain', 'forest', 'drops'].map(id => ({id, available: true}))}], products: {}});
  const snapshot = () => ({volumes: [0, 1, 2].map(i => get('layer-' + i).value),
    timer: document.querySelectorAll('[data-minutes]').filter(b => b.getAttribute('aria-pressed') === 'true').map(b => b.dataset.minutes),
    remaining: get('time-remaining').textContent, icon: get('play-button').querySelector('use').getAttribute('href'),
    playDisabled: get('play-button').disabled});
  return {window, document, get, click, calls, snapshot,
    runTimers() {const pending=[...timers.values()];timers.clear();pending.forEach(callback=>callback());},
    loadScenes(entries = ['rain','shore','fireside','moon'].map(id=>({id,available:true}))) {
      window.loadCatalog({scenes:entries.map(entry=>({title:entry.id,titleEn:entry.id,layers:[0,1,2].map(i=>({id:entry.id+'-'+i,label:'소리 '+i,labelEn:'Sound '+i,available:true,volume:.2+i*.1})),...entry})),products:{}});
    },
    setState(change = {}) { Object.assign(nativeState, change); window.setNativeState({...nativeState}); },
    setReducedMotion(value) {motionMedia.matches=value;motionMedia.dispatchEvent({type:'change',matches:value});},
    setNarrow(narrow) { media.matches = narrow; media.dispatchEvent({type: 'change', matches: narrow}); }};
}
let passed = 0;
function test(name, run) { run(harness()); passed++; console.log('PASS ' + name); }

test('unknown parking stays locked; open, close and Escape manage panel focus', h => {
  assert.equal(h.get('app-shell').inert, true); h.click('mixer-toggle');
  assert.equal(h.get('sound-panel').hidden, true);
  h.setState(); h.calls.length = 0;
  h.click('mixer-toggle');
  assert.equal(h.get('sound-panel').hidden, false);
  assert.equal(h.get('mixer-toggle').getAttribute('aria-expanded'), 'true');
  assert.equal(h.document.activeElement.id, 'close-mixer');
  h.click('close-mixer');
  assert.equal(h.get('sound-panel').hidden, true);
  assert.equal(h.document.activeElement.id, 'mixer-toggle');
  h.click('mixer-toggle'); h.document.dispatchEvent({type: 'keydown', key: 'Escape'});
  assert.equal(h.get('sound-panel').hidden, true);
  assert.equal(h.document.activeElement.id, 'mixer-toggle');
  assert.deepEqual(h.calls, []);
});
test('parking loss closes the mixer and return does not reopen or send playback commands', h => {
  h.setState(); h.click('mixer-toggle'); h.calls.length = 0;
  h.setState({parked: false, audioPlaying: false});
  assert.equal(h.get('sound-panel').hidden, true);
  assert.equal(h.get('app-shell').inert, true);
  assert.equal(h.get('park-lock').hidden, false);
  assert.equal(h.get('play-button').disabled, true);
  h.click('mixer-toggle'); assert.equal(h.get('sound-panel').hidden, true);
  h.setState({parked: true});
  assert.equal(h.get('sound-panel').hidden, true);
  assert.equal(h.get('play-button').querySelector('use').getAttribute('href'), '#i-play');
  assert.deepEqual(h.calls, []);
});
test('narrow-wide-narrow reflow preserves audio and timer and restores hidden panel focus', h => {
  h.setState(); h.click('mixer-toggle'); const before = h.snapshot(); h.calls.length = 0;
  h.setNarrow(false);
  assert.equal(h.get('sound-panel').hidden, false);
  assert.equal(h.get('mixer-toggle').getAttribute('aria-expanded'), 'false');
  assert.deepEqual(h.snapshot(), before);
  h.get('layer-1').focus(); h.setNarrow(true);
  assert.equal(h.get('sound-panel').hidden, true);
  assert.equal(h.document.activeElement.id, 'mixer-toggle');
  assert.deepEqual(h.snapshot(), before);
  assert.deepEqual(h.calls, []);
});
test('language changes preserve the open panel and playback while translating its controls', h => {
  h.setState(); h.click('mixer-toggle'); const before = h.snapshot(); h.calls.length = 0;
  h.click('language-button');
  assert.equal(h.document.documentElement.lang, 'en');
  assert.equal(h.get('mix-title').textContent, 'Sound mix');
  assert.equal(h.get('close-mixer').getAttribute('aria-label'), 'Close');
  assert.equal(h.get('sound-panel').hidden, false);
  assert.deepEqual(h.snapshot(), before);
  assert.deepEqual(h.calls.map(c => c.method), ['savePreferences']);
  assert.equal(JSON.parse(h.calls[0].args[0]).language, 'en');
  h.click('language-button');
  assert.equal(h.get('mix-title').textContent, '소리 조절');
  assert.equal(h.get('sound-panel').hidden, false);
  assert.deepEqual(h.snapshot(), before);
});
test('slider changes survive panel close and reopen and use the native bridge', h => {
  h.setState(); h.click('mixer-toggle'); h.calls.length = 0;
  const slider = h.get('layer-1'); slider.value = '42';
  slider.dispatchEvent({type: 'input'}); slider.dispatchEvent({type: 'change'});
  assert.deepEqual(h.calls[0], {method: 'setLayerVolume', args: [1, .42, 'rain']});
  h.click('close-mixer'); h.click('mixer-toggle');
  assert.equal(h.get('layer-1').value, '42');
  assert.equal(h.calls.length, 1);
});
test('Pause remains usable with the mixer open and updates only from native state', h => {
  h.setState(); h.click('mixer-toggle'); h.calls.length = 0;
  assert.equal(h.get('play-button').disabled, false); h.click('play-button');
  assert.deepEqual(h.calls, [{method: 'pause', args: []}]);
  assert.equal(h.get('sound-panel').hidden, false);
  h.setState({audioPlaying: false});
  assert.equal(h.get('play-button').querySelector('use').getAttribute('href'), '#i-play');
  assert.equal(h.get('sound-panel').hidden, false);
});
test('About closes the mixer; parking loss also closes the open About dialog', h => {
  h.setState(); h.click('mixer-toggle'); h.calls.length = 0;
  h.click('about-button');
  assert.equal(h.get('sound-panel').hidden, true); assert.equal(h.get('about-dialog').open, true);
  h.setState({parked: false, audioPlaying: false});
  assert.equal(h.get('about-dialog').open, false); assert.deepEqual(h.calls, []);
});
test('four catalog scenes become choices, without permanently taking viewport space', h => {
  h.loadScenes();h.setState();h.calls.length=0;h.click('choose-scene');
  assert.equal(h.get('scene-dialog').open,true);
  assert.equal(h.get('scene-choices').children.length,4);
  assert.equal(h.get('choice-rain').getAttribute('aria-pressed'),'true');
  assert.equal(h.document.activeElement.id,'choice-rain');
  assert.equal(h.document.getElementById('scene-tabs'),null);
  h.click('close-scenes');assert.equal(h.get('scene-dialog').open,false);
  assert.equal(h.document.activeElement.id,'choose-scene');assert.deepEqual(h.calls,[]);
});
test('first launch asks after parking is confirmed; returning users keep their last space', h => {
  h.loadScenes();assert.equal(h.get('scene-dialog').open,false);
  h.setState({parked:false,hasSelectedScene:false});assert.equal(h.get('scene-dialog').open,false);
  h.setState({parked:true});assert.equal(h.get('scene-dialog').open,true);
  h.click('close-scenes');h.setState();assert.equal(h.get('scene-dialog').open,false);
  h.setState({hasSelectedScene:true,sceneId:'moon',volumes:[.1,.2,.3],audioPlaying:false});
  assert.equal(h.get('scene-dialog').open,false);assert.equal(h.get('scene-title').textContent,'moon');
  assert.deepEqual(h.snapshot().volumes,['10','20','30']);
});
test('scene selection waits for native restored volumes and never overwrites them with defaults', h => {
  h.loadScenes();h.setState();h.click('choose-scene');h.calls.length=0;
  const before=h.snapshot();h.click('choice-shore');
  assert.deepEqual(h.calls,[{method:'selectScene',args:['shore']}]);
  assert.equal(h.get('scene-title').textContent,'rain');
  assert.deepEqual(h.snapshot().volumes,before.volumes);
  assert.equal(h.get('scene-dialog').open,true);assert.equal(h.get('play-button').disabled,true);
  h.setState({sceneId:'shore',volumes:[.77,.31,.09],remainingSeconds:900,audioPlaying:false});
  assert.equal(h.get('scene-title').textContent,'shore');assert.equal(h.get('scene-dialog').open,false);
  assert.deepEqual(h.snapshot().volumes,['77','31','9']);assert.equal(h.get('time-remaining').textContent,'15:00');
  assert.equal(h.document.activeElement.id,'choose-scene');
  assert.deepEqual(h.calls,[{method:'selectScene',args:['shore']}]);
  h.click('language-button');assert.deepEqual(JSON.parse(h.calls[1].args[0]),{language:'en'});
});
test('choosing the default scene confirms the first choice; reselecting later keeps playback', h => {
  h.loadScenes();h.setState({hasSelectedScene:false,audioPlaying:false});h.calls.length=0;
  h.click('choice-rain');assert.deepEqual(h.calls,[{method:'selectScene',args:['rain']}]);
  h.setState({hasSelectedScene:true});assert.equal(h.get('scene-dialog').open,false);
  h.setState({audioPlaying:true});h.click('choose-scene');h.calls.length=0;h.click('choice-rain');
  assert.deepEqual(h.calls,[]);assert.equal(h.snapshot().icon,'#i-pause');
});
test('parking loss closes choices, cancels pending UI and never starts audio on return', h => {
  h.loadScenes();h.setState();h.click('choose-scene');h.click('choice-shore');h.calls.length=0;
  h.setState({parked:false,audioPlaying:false});assert.equal(h.get('scene-dialog').open,false);
  assert.equal(h.get('layer-0').disabled,true);
  h.get('layer-0').value='99';h.get('layer-0').dispatchEvent({type:'input'});
  h.click('choose-scene');assert.equal(h.get('scene-dialog').open,false);
  h.setState({parked:true,sceneId:'shore',volumes:[.4,.3,.2]});
  assert.equal(h.get('scene-dialog').open,false);assert.equal(h.snapshot().icon,'#i-play');
  assert.equal(h.get('layer-0').disabled,false);assert.equal(h.get('layer-0').value,'40');
  assert.deepEqual(h.calls,[]);
});
test('new valid scene IDs are data driven; invalid, duplicate and unavailable choices are safe', h => {
  h.loadScenes([{id:'rain',available:true},{id:'snow-hut',available:true,title:'눈의 오두막',titleEn:'Snow Hut'},
    {id:'snow-hut',available:true,title:'duplicate'},{id:'../../bad',available:true},{id:'pending',available:false}]);
  h.setState();h.click('choose-scene');h.calls.length=0;
  assert.equal(h.get('scene-choices').children.length,3);assert.equal(h.get('choice-pending').disabled,true);
  h.click('choice-pending');assert.deepEqual(h.calls,[]);
  h.click('choice-snow-hut');h.setState({sceneId:'snow-hut',audioPlaying:false,volumes:[.5,.4,.3]});
  assert.equal(h.get('scene-title').textContent,'눈의 오두막');
  h.click('language-button');assert.equal(h.get('scene-title').textContent,'Snow Hut');
});
test('selection timeout allows retry without starting audio or changing the saved mix', h => {
  h.loadScenes();h.setState();h.click('choose-scene');h.calls.length=0;
  h.click('choice-moon');h.click('choice-shore');assert.equal(h.calls.length,1);
  assert.equal(h.get('layer-0').disabled,true);
  h.runTimers();assert.equal(h.get('scene-title').textContent,'rain');
  assert.equal(h.get('layer-0').disabled,false);
  assert.equal(h.get('choice-shore').disabled,false);assert.equal(h.get('toast').hidden,false);
  h.click('choice-shore');assert.deepEqual(h.calls.map(c=>c.method),['selectScene','selectScene']);
});
test('closing a pending scene picker cannot write the old mix; native acknowledgement restores sliders', h => {
  h.loadScenes();h.setState();h.click('choose-scene');h.calls.length=0;
  const oldSlider=h.get('layer-0');h.click('choice-shore');h.click('close-scenes');h.click('mixer-toggle');
  assert.equal(h.get('sound-panel').hidden,false);assert.equal(oldSlider.disabled,true);
  oldSlider.value='91';oldSlider.dispatchEvent({type:'input'});
  assert.deepEqual(h.calls,[{method:'selectScene',args:['shore']}]);
  // An older state response and a language rerender must keep the inputs locked.
  h.setState();assert.equal(h.get('layer-0').disabled,true);h.click('language-button');
  assert.equal(h.get('layer-0').disabled,true);
  assert.equal(h.get('layer-value-0').textContent,'25%');
  h.setState({sceneId:'shore',volumes:[.77,.31,.09],audioPlaying:false,language:'en'});
  assert.equal(h.get('layer-0').disabled,false);assert.equal(h.get('layer-0').value,'77');
  const count=h.calls.length;
  oldSlider.value='98';oldSlider.dispatchEvent({type:'input'});assert.equal(h.calls.length,count);
  const restored=h.get('layer-0');restored.value='53';restored.dispatchEvent({type:'input'});
  assert.deepEqual(h.calls.at(-1),{method:'setLayerVolume',args:[0,.53,'shore']});
});
test('same-space first-choice acknowledgement and unavailable bridge both unlock the sliders', h => {
  h.loadScenes();h.setState({hasSelectedScene:false,audioPlaying:false});
  h.click('choice-rain');assert.equal(h.get('layer-0').disabled,true);
  h.window.setNativeState({sceneId:'rain',hasSelectedScene:true});
  assert.equal(h.get('layer-0').disabled,false);
  delete h.window.Android.selectScene;h.click('choose-scene');h.click('choice-moon');
  assert.equal(h.get('layer-0').disabled,false);assert.equal(h.get('play-button').disabled,false);
  assert.equal(h.get('scene-title').textContent,'rain');
});
test('creation cards only show supplied prompts as text and retain QR links', h => {
  h.loadScenes([{id:'rain',available:true},{id:'shore',available:true,title:'노을 해변',
    creation:{model:{prompt:'A quiet beach <b>literal text</b>',description:'실제 제작 설명'},sound:{}},
    layers:[{id:'waves',label:'파도',labelEn:'Waves',available:true,creationPrompt:'Soft waves, no voices.'}]}]);
  h.setState();h.click('about-button');assert.equal(h.get('model-creation').children.length,0);
  h.setState({sceneId:'shore',audioPlaying:false});
  assert.equal(h.get('creation-scene-title').textContent,'노을 해변');
  assert.equal(h.get('model-creation-description').textContent,'실제 제작 설명');
  assert.equal(h.get('model-creation').querySelector('p').textContent,'A quiet beach <b>literal text</b>');
  assert.equal(h.get('model-creation').querySelector('b'),null);
  assert.equal(h.get('sound-creation').querySelector('p').textContent,'Soft waves, no voices.');
  h.calls.length=0;
  h.document.querySelectorAll('[data-product]').find(button=>button.dataset.product==='threeD').dispatchEvent({type:'click'});
  assert.deepEqual(h.calls,[{method:'openProduct',args:['threeD']}]);
});
test('scene cards use bundled thumbnails only and missing catalog data remains usable', h => {
  h.loadScenes([{id:'rain',available:true,thumbnailUrl:'/content/rain/thumbnail.webp'},
    {id:'shore',available:true,thumbnailUrl:'https://example.com/image.png'},
    {id:'moon',available:true,thumbnailUrl:'/content/../secret.png'}]);
  assert.equal(h.get('choice-rain').querySelector('img').src,'/content/rain/thumbnail.webp');
  assert.equal(h.get('choice-shore').querySelector('img'),null);assert.equal(h.get('choice-moon').querySelector('img'),null);
  h.window.loadCatalog({scenes:[]});h.setState();
  assert.equal(h.get('choose-scene').hidden,true);assert.equal(h.get('play-button').disabled,true);
});
test('motion control is local, accessible, translated, and survives UI rerenders without restarting audio',h=>{
  const emitted=[];h.window.addEventListener('space:motion',event=>emitted.push(event.detail.enabled));
  h.setState();assert.equal(h.get('motion-toggle').disabled,true,'Wait for the model');
  h.window.spaceViewStatus='ready';h.window.spaceMotionAvailable=true;h.window.dispatchEvent({type:'space:modelLoaded'});
  h.calls.length=0;assert.equal(h.get('motion-toggle').disabled,false);assert.equal(h.get('motion-label').textContent,'움직임 켜짐');
  h.click('motion-toggle');assert.equal(h.window.spaceMotionEnabled,false);assert.equal(emitted.at(-1),false);
  assert.equal(h.get('motion-toggle').getAttribute('aria-pressed'),'false');assert.equal(h.get('motion-label').textContent,'움직임 꺼짐');
  h.setState();assert.equal(h.window.spaceMotionEnabled,false);assert.deepEqual(h.calls,[]);
  h.click('language-button');assert.equal(h.get('motion-label').textContent,'Motion off');
  assert.equal(h.get('motion-toggle').getAttribute('aria-label'),'Turn space motion on');
  h.calls.length=0;h.click('motion-toggle');assert.equal(h.window.spaceMotionEnabled,true);assert.deepEqual(h.calls,[]);
  h.setState({parked:false,audioPlaying:false});assert.equal(h.get('motion-toggle').disabled,true);
});
test('reduced motion and missing rendering keep the control disabled without changing playback',h=>{
  h.setState();h.window.spaceViewStatus='ready';h.window.spaceMotionAvailable=true;h.window.dispatchEvent({type:'space:modelLoaded'});
  h.calls.length=0;const before=h.snapshot();h.setReducedMotion(true);
  assert.equal(h.window.spaceMotionEnabled,false);assert.equal(h.get('motion-toggle').disabled,true);
  assert.equal(h.get('motion-label').textContent,'움직임 꺼짐');h.click('motion-toggle');assert.equal(h.window.spaceMotionEnabled,false);
  assert.deepEqual(h.snapshot(),before);assert.deepEqual(h.calls,[]);
  h.setReducedMotion(false);assert.equal(h.window.spaceMotionEnabled,true);assert.equal(h.get('motion-toggle').disabled,false);
  h.window.spaceMotionAvailable=false;h.window.dispatchEvent({type:'space:modelLoaded'});assert.equal(h.get('motion-toggle').disabled,true);
  assert.deepEqual(h.calls,[]);
});
console.log(`Web mixer and spaces behavior tests passed: ${passed} scenarios.`);
