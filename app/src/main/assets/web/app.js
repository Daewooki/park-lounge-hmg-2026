/* Local-only UI. Playback, lifecycle, parking and QR display are owned by Android. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const hasNative = () => !!window.Android;
  const preview = !hasNative() && new URLSearchParams(location.search).get('preview') === '1';
  const translations = {
    ko: {
      parked:'주차 중',appSubtitle:'차 안에서 만나는 나만의 휴식 공간',rainTitle:'비 오는 숲',shoreTitle:'노을 해변',firesideTitle:'벽난로 라운지',moonTitle:'달 전망 라운지',
      rainDescription:'창밖의 빗소리, 잠깐의 여유',shoreDescription:'잔잔한 파도 너머로 저무는 하루',firesideDescription:'따뜻한 불빛 곁에서 쉬어가는 시간',moonDescription:'지구를 바라보며 만나는 고요',
      mixTitle:'소리 조절',mixDescription:'세 가지 소리를 원하는 만큼 더해보세요.',timer:'머무를 시간',minutes:'분',motionOn:'움직임 켜짐',motionOff:'움직임 꺼짐',motionEnable:'공간의 움직임 켜기',motionDisable:'공간의 움직임 끄기',motionReduced:'동작 줄이기 설정에 따라 움직임이 꺼져 있습니다.',
      previewLabel:'공간 미리보기',realModelLabel:'VARCO 3D로 만든 에셋',previewBadge:'브라우저 미리보기 · 음원 없음',exploreHint:'드래그해 둘러보기',exploreAria:'드래그하거나 좌우 방향키로 둘러보세요.',viewLoading:'3D 공간을 불러오고 있어요',viewRecovering:'3D 화면을 복구하고 있어요',viewUnavailable:'3D 화면을 표시할 수 없어요',viewLoadFailed:'3D 공간을 불러오지 못했어요',
      footerNote:'오늘의 짧은 쉼, 새로운 상상의 시작.',aboutCTA:'소개',aboutTitle:'이 공간은 어떻게 만들었을까요?',technologyLabel:'사용 기술',productLinks:'사용 기술 알아보기',
      aboutIntro:'3D 공간은 VARCO 3D로, 환경음은 VARCO Sound로 미리 제작했습니다.',soundProduct:'장면에 어울리는 소리를 만들고 조합했어요.',threeProduct:'아이디어를 입체적인 공간으로',qrCTA:'휴대폰으로 만나보기',makeCTA:'사용 기술 알아보기',developerCTA:'VARCO API 참고 링크 ↗',privacy:'개인정보 처리방침',report:'콘텐츠 문의',
      changeSpace:'공간 바꾸기',chooseTitle:'오늘은 어디에서 쉴까요?',chooseDescription:'마음이 머무는 공간을 골라보세요.',currentSpace:'현재 공간',enterSpace:'이 공간에서 쉬기',selectingSpace:'공간을 바꾸고 있어요',generationPrompt:'실제 제작 문장 보기',referenceImage:'제작에 사용한 참고 이미지',layer:'소리',spaceFallback:'휴식 공간',chooseUnavailable:'아직 준비 중인 공간입니다.',
      waiting:'음원 준비 중',play:'휴식 시작',pause:'일시정지',starting:'준비 중',unavailable:'검증된 음원이 연결되면 재생할 수 있어요.',ready:'주차 상태에서만 재생됩니다.',playingNote:'설정한 시간이 지나면 소리가 멈춥니다.',
      nativeOnly:'차량 앱에서 QR 코드를 확인할 수 있어요.',lockUnknown:'주차 상태를 확인하고 있어요',lockDriving:'주차 후 이용할 수 있어요',lockDescription:'안전하게 주차한 뒤, 나만의 공간을 만나보세요.',
      attributionPreview:'이 공간의 제작 정보는 콘텐츠를 불러온 뒤 표시합니다.',
      attributionAssets:'',close:'닫기',sceneNav:'공간 선택',timerAria:'재생 시간',notReady:'준비 중',remaining:'남은 시간',error:'처리할 수 없어요. 잠시 후 다시 시도해 주세요.'
    },
    en: {
      parked:'Parked',appSubtitle:'Your in-car retreat',rainTitle:'Rain Shelter',shoreTitle:'Sunset Shore',firesideTitle:'Fireside Lounge',moonTitle:'Moon Lounge',
      rainDescription:'A little time to yourself, surrounded by rain.',shoreDescription:'Let the day drift away with the tide.',firesideDescription:'Stay a while beside the warm glow.',moonDescription:'A quiet moment with a view of Earth.',
      mixTitle:'Sound mix',mixDescription:'Find your balance of three gentle sounds.',timer:'Time to unwind',minutes:'min',motionOn:'Motion on',motionOff:'Motion off',motionEnable:'Turn space motion on',motionDisable:'Turn space motion off',motionReduced:'Motion is off to respect your reduced motion setting.',previewLabel:'Space preview',realModelLabel:'Asset made with VARCO 3D',previewBadge:'Browser preview · No audio',exploreHint:'Drag to explore',exploreAria:'Drag or use the left and right arrow keys to explore.',viewLoading:'Loading your 3D space',viewRecovering:'Restoring the 3D view',viewUnavailable:'The 3D view is unavailable',viewLoadFailed:'Could not load this 3D space.',
      footerNote:'A small pause. A new space for imagination.',aboutCTA:'About',aboutTitle:'How was this space made?',technologyLabel:'Technologies used',productLinks:'Explore the technologies',aboutIntro:'The 3D spaces and ambient sounds were created in advance with VARCO 3D and VARCO Sound.',soundProduct:'Sounds created and mixed to bring this scene to life.',threeProduct:'An idea brought into three dimensions.',qrCTA:'Explore on your phone',makeCTA:'Explore this technology',developerCTA:'VARCO API reference ↗',privacy:'Privacy policy',report:'Contact',
      changeSpace:'Spaces',chooseTitle:'Where will you unwind?',chooseDescription:'Choose a space that feels right today.',currentSpace:'Current space',enterSpace:'Unwind here',selectingSpace:'Changing your space',generationPrompt:'See the original prompt',referenceImage:'Reference image used to create this space',layer:'Sound',spaceFallback:'A space to unwind',chooseUnavailable:'This space is not ready yet.',
      waiting:'Unavailable',play:'Start break',pause:'Pause',starting:'Preparing',unavailable:'Playback is available once verified audio is connected.',ready:'Available while parked.',playingNote:'Audio stops when your selected time is up.',nativeOnly:'Open the vehicle app to see the QR code.',lockUnknown:'Checking parking status',lockDriving:'Available after you park',lockDescription:'Park safely, then find a little space for yourself.',
      attributionPreview:'Production details will appear when this space has loaded.',attributionAssets:'',close:'Close',sceneNav:'Choose a space',timerAria:'Playback duration',notReady:'Not ready',remaining:'Time remaining',error:'Something went wrong. Please try again.'
    }
  };
  const fallbackScene={id:'rain',available:false,layers:[]};
  const icons={rain:['rain','leaf','rain'],shore:['wave','wind','wave'],fireside:['fire','spark','wind'],moon:['wind','orbit','spark']};
  const safeIcons=new Set(['rain','fire','orbit','leaf','wind','moon','volume','spark','wave']);
  const englishLayers={rain:'Rainfall',leaves:'Leaves',forest:'Forest breeze',wind:'Forest breeze',drops:'Raindrops',fire:'Firewood',night:'Night forest',breeze:'Gentle breeze',space:'Deep resonance',cabin:'Cabin air',chime:'Gentle echoes',hum:'Deep resonance',orbit:'Orbital waves',waves:'Gentle waves',sea:'Sea breeze',pebbles:'Water over pebbles'};
  const state={parked:preview,knownParking:preview,sceneId:'rain',language:'ko',volumes:[.65,.35,.2],timerMinutes:10,remainingSeconds:600,audioPlaying:false,pending:false,preparing:false,error:null,hasSelectedScene:preview?false:null,selectingScene:null};
  const activeSliders=new Set();
  const narrowLayout=window.matchMedia('(max-aspect-ratio:6/5), (max-width:760px)');
  const motionPreference=window.matchMedia('(prefers-reduced-motion: reduce)');
  // Session-only preference: no new persisted data or network request.
  let motionEnabled=true;
  let mixerOpen=false;
  // The mixer is a nonmodal panel. Playback stays available while it is open.
  function setMixerOpen(open,restoreFocus=false){
    mixerOpen=!!open&&allowed()&&narrowLayout.matches;
    syncMixer();
    if(mixerOpen)$('close-mixer').focus();
    else if(restoreFocus&&allowed()&&narrowLayout.matches)$('mixer-toggle').focus();
  }
  function syncMixer(){
    const narrow=narrowLayout.matches;
    $('sound-panel').hidden=narrow&&!mixerOpen;
    $('sound-panel').classList.toggle('expanded',narrow&&mixerOpen);
    $('mixer-toggle').setAttribute('aria-expanded',String(narrow&&mixerOpen));
  }
  narrowLayout.addEventListener('change',()=>{const focusInMixer=$('sound-panel').contains(document.activeElement);mixerOpen=false;syncMixer();if(focusInMixer&&narrowLayout.matches&&allowed())$('mixer-toggle').focus();});
  let catalog={scenes:[fallbackScene],products:{}},catalogLoaded=false,firstChoiceShown=false,toastTimeout,pendingTimeout,selectionTimeout;
  const previewVolumes=new Map();
  const t=key=>translations[state.language][key] ?? key;
  const scene=()=>catalog.scenes.find(s=>s.id===state.sceneId)||fallbackScene;
  const clamp=(v,fallback=.5)=>Number.isFinite(Number(v))?Math.min(1,Math.max(0,Number(v))):fallback;
  const sceneVolumes=current=>[0,1,2].map(i=>clamp((current.layers||[])[i]?.volume,[.65,.35,.2][i]));
  const localized=(entry,key)=>state.language==='en'?(entry[key+'En']||entry[key]||''):(entry[key]||entry[key+'En']||'');
  const sceneTitle=current=>localized(current,'title')||translations[state.language][current.id+'Title']||t('spaceFallback');
  const sceneDescription=current=>localized(current,'subtitle')||localized(current,'description')||translations[state.language][current.id+'Description']||'';
  const layerTitle=(layer,index)=>(state.language==='en'?(layer.labelEn||englishLayers[layer.id]||layer.label):(layer.label||layer.labelEn))||t('layer')+' '+(index+1);
  // Images are bundled app assets. Catalog metadata never opens a remote URL in the WebView.
  const localImage=value=>typeof value==='string'&&/^(?:\/?content\/|brand\/)[A-Za-z0-9_./-]+\.(?:png|webp|jpg|jpeg)$/i.test(value)&&!value.split('/').includes('..')?value:null;
  const allowed=()=>state.knownParking && state.parked;
  function syncMotionControl(){
    const enabled=motionEnabled&&!motionPreference.matches,button=$('motion-toggle');
    $('motion-label').textContent=t(enabled?'motionOn':'motionOff');
    button.setAttribute('aria-pressed',String(enabled));
    const label=t(motionPreference.matches?'motionReduced':enabled?'motionDisable':'motionEnable');
    button.setAttribute('aria-label',label);button.setAttribute('title',label);
    button.disabled=!allowed()||motionPreference.matches||window.spaceViewStatus!=='ready'||window.spaceMotionAvailable===false;
    window.spaceMotionEnabled=enabled;
    window.dispatchEvent(new CustomEvent('space:motion',{detail:{enabled}}));
  }
  function native(method,...args){
    if(!hasNative() || typeof window.Android[method]!=='function') return false;
    try {window.Android[method](...args);return true;} catch (_) {showToast(t('error'));return false;}
  }
  function save(){native('savePreferences',JSON.stringify({language:state.language}));}
  function showToast(message){$('toast').textContent=message;$('toast').hidden=false;clearTimeout(toastTimeout);toastTimeout=setTimeout(()=>$('toast').hidden=true,4500);}
  function icon(name){const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');const use=document.createElementNS('http://www.w3.org/2000/svg','use');use.setAttribute('href','#i-'+name);svg.appendChild(use);return svg;}
  function updateParking(){
    const locked=!allowed();
    $('park-lock').hidden=!locked;
    $('app-shell').inert=locked;
    $('app-shell').setAttribute('aria-hidden',String(locked));
    $('lock-title').textContent=t(state.knownParking?'lockDriving':'lockUnknown');
    $('lock-description').textContent=t('lockDescription');
    if(locked){setMixerOpen(false);if($('about-dialog').open)$('about-dialog').close();if($('scene-dialog').open)$('scene-dialog').close();$('toast').hidden=true;}
    window.dispatchEvent(new CustomEvent('space:activity',{detail:{parked:allowed(),playing:state.audioPlaying,visible:!document.hidden}}));
    syncMotionControl();
  }
  function renderLayers(){
    const container=$('layers');container.replaceChildren();
    for(let i=0;i<3;i++){
      const layer=(scene().layers||[])[i]||{available:false};
      const labelText=layerTitle(layer,i);
      const wrap=document.createElement('div');wrap.className='layer';
      const label=document.createElement('label');label.htmlFor='layer-'+i;label.className='layer-label';label.appendChild(icon(safeIcons.has(layer.icon)?layer.icon:icons[state.sceneId]?.[i]||'volume'));
      const title=document.createElement('span');title.textContent=labelText;label.appendChild(title);
      const value=document.createElement('span');value.className='layer-value';value.id='layer-value-'+i;value.textContent=Math.round(state.volumes[i]*100)+'%';label.appendChild(value);
      const input=document.createElement('input');input.type='range';input.id='layer-'+i;input.className='slider';input.min='0';input.max='100';input.step='1';input.value=String(Math.round(state.volumes[i]*100));input.style.setProperty('--level',input.value+'%');input.disabled=!allowed()||!!state.selectingScene||!scene().available||!layer.available;input.setAttribute('aria-valuetext',input.value+'%');
      const renderedSceneId=state.sceneId;
      input.addEventListener('input',()=>{if(!allowed()||state.selectingScene||state.sceneId!==renderedSceneId||input.disabled)return;state.volumes[i]=Number(input.value)/100;input.style.setProperty('--level',input.value+'%');input.setAttribute('aria-valuetext',input.value+'%');value.textContent=input.value+'%';native('setLayerVolume',i,state.volumes[i],renderedSceneId);});
      input.addEventListener('pointerdown',()=>{if(!input.disabled)activeSliders.add(i);});
      input.addEventListener('change',()=>{activeSliders.delete(i);if(preview)previewVolumes.set(state.sceneId,[...state.volumes]);});wrap.append(label,input);container.appendChild(wrap);
    }
  }
  function updateLayers(){
    for(let i=0;i<3;i++){
      const input=$('layer-'+i);if(!input)continue;
      const layer=(scene().layers||[])[i]||{};
      input.disabled=!allowed()||!!state.selectingScene||!scene().available||!layer.available;
      if(activeSliders.has(i))continue;
      const value=String(Math.round(state.volumes[i]*100));
      input.value=value;input.style.setProperty('--level',value+'%');input.setAttribute('aria-valuetext',value+'%');$('layer-value-'+i).textContent=value+'%';
    }
  }
  function closeSceneChoices(restoreFocus=true){
    if($('scene-dialog').open)$('scene-dialog').close();
    if(restoreFocus&&allowed())$('choose-scene').focus();
  }
  function openSceneChoices(){
    if(!allowed()||catalog.scenes.length<2)return;
    setMixerOpen(false);
    if($('about-dialog').open)$('about-dialog').close();
    renderSceneChoices();
    if(!$('scene-dialog').open)$('scene-dialog').showModal();
    const selected=$('choice-'+state.sceneId);
    if(selected&&!selected.disabled)selected.focus();else $('close-scenes').focus();
  }
  function maybeChooseFirstScene(){
    if(catalogLoaded&&allowed()&&state.hasSelectedScene===false&&!firstChoiceShown&&catalog.scenes.length>1){firstChoiceShown=true;openSceneChoices();}
  }
  function chooseScene(id){
    const next=catalog.scenes.find(entry=>entry.id===id);
    if(!allowed()||state.selectingScene||!next)return;
    if(!next.available){showToast(t('chooseUnavailable'));return;}
    if(id===state.sceneId&&state.hasSelectedScene===true){closeSceneChoices();return;}
    activeSliders.clear();
    if(preview){
      previewVolumes.set(state.sceneId,[...state.volumes]);state.sceneId=id;
      state.volumes=[...(previewVolumes.get(id)||sceneVolumes(next))];
      state.hasSelectedScene=true;state.audioPlaying=false;state.remainingSeconds=state.timerMinutes*60;
      renderScene();renderPlayback();closeSceneChoices();return;
    }
    // Android owns scene selection and restores that scene's saved mix atomically.
    // Do not write default volumes before its state response arrives.
    state.selectingScene=id;renderSceneChoices();renderPlayback();updateLayers();
    clearTimeout(selectionTimeout);
    selectionTimeout=setTimeout(()=>{if(state.selectingScene){state.selectingScene=null;renderSceneChoices();renderPlayback();updateLayers();showToast(t('error'));}},10000);
    if(!native('selectScene',id)){clearTimeout(selectionTimeout);state.selectingScene=null;renderSceneChoices();renderPlayback();updateLayers();showToast(t('nativeOnly'));}
  }
  function renderSceneChoices(){
    const container=$('scene-choices');container.replaceChildren();
    catalog.scenes.forEach(entry=>{
      const selected=entry.id===state.sceneId&&state.hasSelectedScene===true;
      const button=document.createElement('button');button.id='choice-'+entry.id;button.type='button';button.className='scene-choice';button.dataset.scene=entry.id;button.setAttribute('aria-pressed',String(selected));button.classList.toggle('selected',selected);button.disabled=!entry.available||!!state.selectingScene;
      const art=document.createElement('span');art.className='choice-art';art.setAttribute('aria-hidden','true');
      const thumbnail=localImage(entry.thumbnailUrl);
      if(thumbnail){const image=document.createElement('img');image.src=thumbnail.startsWith('content/')?'/'+thumbnail:thumbnail;image.alt='';image.addEventListener('error',()=>{image.hidden=true;art.appendChild(icon(icons[entry.id]?.[0]||'spark'));});art.appendChild(image);}else art.appendChild(icon(icons[entry.id]?.[0]||'spark'));
      const copy=document.createElement('span');copy.className='choice-copy';
      const title=document.createElement('strong');title.textContent=sceneTitle(entry);
      const description=document.createElement('span');description.className='choice-description';description.textContent=localized(entry,'choiceSubtitle')||sceneDescription(entry);
      const action=document.createElement('span');action.className='choice-action';action.appendChild(icon(selected?'spark':'arrow'));const label=document.createElement('span');label.textContent=t(!entry.available?'notReady':selected?'currentSpace':'enterSpace');action.appendChild(label);
      copy.append(title,description,action);button.append(art,copy);button.addEventListener('click',()=>chooseScene(entry.id));container.appendChild(button);
    });
    $('scene-choice-status').hidden=!state.selectingScene;
    $('scene-choice-status').textContent=state.selectingScene?t('selectingSpace'):'';
  }
  function renderCreationDetails(){
    const current=scene(),creation=current.creation||{};
    $('creation-scene-title').textContent=sceneTitle(current);
    const model=creation.model||{},sound=creation.sound||{};
    $('model-creation-description').textContent=localized(model,'description')||t('threeProduct');
    $('sound-creation-description').textContent=localized(sound,'description')||t('soundProduct');
    function detail(container,heading,prompt){
      if(typeof prompt!=='string'||!prompt.trim())return;
      const item=document.createElement('details');
      const summary=document.createElement('summary');summary.textContent=heading;
      const text=document.createElement('p');text.className='creation-prompt';text.textContent=prompt;
      item.append(summary,text);container.appendChild(item);
    }
    const modelContainer=$('model-creation');modelContainer.replaceChildren();
    const reference=localImage(model.referenceImageUrl);
    if(reference){const image=document.createElement('img');image.src=reference.startsWith('content/')?'/'+reference:reference;image.alt=t('referenceImage');image.className='creation-reference';modelContainer.appendChild(image);}
    detail(modelContainer,t('generationPrompt'),localized(model,'prompt'));
    const soundContainer=$('sound-creation');soundContainer.replaceChildren();
    detail(soundContainer,t('generationPrompt'),localized(sound,'prompt'));
    (current.layers||[]).forEach((layer,index)=>detail(soundContainer,layerTitle(layer,index)+' · '+t('generationPrompt'),localized(layer,'creationPrompt')));
  }
  function renderScene(){
    const current=scene();
    $('scene-title').textContent=sceneTitle(current);
    $('scene-description').textContent=sceneDescription(current);
    $('scene-description').style.whiteSpace='pre-line';
    $('scene-viewport').setAttribute('aria-label',$('scene-title').textContent+' · '+t('previewLabel')+'. '+t('exploreAria'));
    $('choose-scene').hidden=catalog.scenes.length<2;
    window.currentSpaceScene={...current,id:state.sceneId};
    window.dispatchEvent(new CustomEvent('space:scene',{detail:window.currentSpaceScene}));
    updateAttribution();renderLayers();renderSceneChoices();renderCreationDetails();
  }
  function updateAttribution(){
    const current=scene();
    const attribution=state.language==='en'?(current.attributionEn||current.attribution):current.attribution;
    const connected=current.available===true&&window.loadedSpaceModel===state.sceneId;
    $('attribution').textContent=connected&&typeof attribution==='string'&&attribution.trim()?t('attributionAssets')+attribution:t('attributionPreview');
    // Generation provenance and distribution rights are separate; this only labels the loaded model.
    const provenance=current.modelProvenance||{};
    const sourceVerified=provenance.generationSourceVerified===true||provenance.rightsConfirmed===true;
    const isVarco=!!current.modelUrl&&provenance.product==='VARCO3D'&&sourceVerified&&typeof provenance.sourceNote==='string'&&provenance.sourceNote.trim()&&window.loadedSpaceModel===state.sceneId;
    // The local 3D module may still be parsing; keep a visible, localized loading state.
    const viewState=window.spaceViewStatus||'loading';
    const statusKey={loading:'viewLoading',lost:'viewRecovering',unavailable:'viewUnavailable',error:'viewLoadFailed'}[viewState];
    $('scene-status').hidden=!statusKey;
    $('scene-status').textContent=statusKey?t(statusKey):'';
    $('explore-hint').hidden=viewState!=='ready';
    const label=$('scene-caption').querySelector('span:last-child');label.textContent=t(viewState==='lost'?'viewRecovering':viewState==='unavailable'?'viewUnavailable':isVarco?'realModelLabel':'previewLabel');
    $('scene-viewport').setAttribute('aria-label',$('scene-title').textContent+' · '+label.textContent+'. '+t('exploreAria'));
    syncMotionControl();
  }
  function renderPlayback(){
    const available=scene().available===true && (scene().layers||[]).some(l=>l.available===true);
    const busy=state.pending||state.preparing||!!state.selectingScene;
    const button=$('play-button');button.disabled=!allowed()||!available||busy;
    $('play-label').textContent=t(busy?'starting':!available?'waiting':state.audioPlaying?'pause':'play');
    button.querySelector('use').setAttribute('href',state.audioPlaying?'#i-pause':'#i-play');
    const note=$('availability-note');note.textContent=state.error?String(state.error):t(!available?'unavailable':state.audioPlaying?'playingNote':'ready');note.classList.toggle('error',!!state.error);
    const seconds=Number.isFinite(state.remainingSeconds)?Math.max(0,Math.floor(state.remainingSeconds)):state.timerMinutes*60;
    $('time-remaining').textContent=String(Math.floor(seconds/60)).padStart(2,'0')+':'+String(seconds%60).padStart(2,'0');
    $('time-remaining').setAttribute('aria-label',t('remaining')+' '+$('time-remaining').textContent);
    document.querySelectorAll('[data-minutes]').forEach(button=>{const selected=Number(button.dataset.minutes)===state.timerMinutes;button.classList.toggle('selected',selected);button.setAttribute('aria-pressed',String(selected));button.disabled=!allowed();});
  }
  function translate(){
    document.documentElement.lang=state.language;
    document.querySelectorAll('[data-i18n]').forEach(el=>el.textContent=t(el.dataset.i18n));
    $('language-button').querySelector('span').textContent=state.language==='ko'?'EN':'한국어';
    $('language-button').setAttribute('aria-label',state.language==='ko'?'Switch language to English':'한국어로 변경');
    $('preview-badge').textContent=t('previewBadge');$('close-about').setAttribute('aria-label',t('close'));$('close-mixer').setAttribute('aria-label',t('close'));$('close-scenes').setAttribute('aria-label',t('close'));syncMixer();
    document.querySelectorAll('.product-shortcut').forEach(button=>button.setAttribute('aria-label',(button.dataset.product==='sound'?'VARCO Sound':'VARCO 3D')+' · '+t('qrCTA')));
    $('choose-scene').setAttribute('aria-label',t('sceneNav'));document.querySelector('.timer-options').setAttribute('aria-label',t('timerAria'));
    renderScene();renderPlayback();updateParking();
  }
  window.loadCatalog=function(value){
    try{if(typeof value==='string')value=JSON.parse(value);if(!value||!Array.isArray(value.scenes))return;
      const entries=value.scenes.filter(s=>s&&typeof s.id==='string'&&/^[a-z][a-z0-9-]{0,47}$/.test(s.id));
      catalog={...value,scenes:entries.filter((s,i)=>entries.findIndex(entry=>entry.id===s.id)===i).map(s=>({...fallbackScene,...s,available:s.available===true,layers:Array.isArray(s.layers)?s.layers:[]}))};
      catalogLoaded=true;
      if(catalog.scenes.length&&!catalog.scenes.some(s=>s.id===state.sceneId)){
        state.sceneId=catalog.scenes[0].id;
        state.volumes=sceneVolumes(scene());
      }
      renderScene();renderPlayback();maybeChooseFirstScene();
    }catch(_){showToast(t('error'));}
  };
  window.setNativeState=function(value){
    try{if(typeof value==='string')value=JSON.parse(value);if(!value||typeof value!=='object')return;
      const beforeScene=state.sceneId,beforeLanguage=state.language,beforeParked=allowed();
      if(typeof value.parked==='boolean'){state.parked=value.parked;state.knownParking=true;}
      if(catalog.scenes.some(s=>s.id===value.sceneId))state.sceneId=value.sceneId;
      if(beforeScene!==state.sceneId)activeSliders.clear();
      if(typeof value.hasSelectedScene==='boolean')state.hasSelectedScene=value.hasSelectedScene;
      if(value.language==='en'||value.language==='ko')state.language=value.language;
      if([5,10,15].includes(Number(value.timerMinutes)))state.timerMinutes=Number(value.timerMinutes);
      if(Array.isArray(value.volumes))state.volumes=[0,1,2].map(i=>activeSliders.has(i)?state.volumes[i]:clamp(value.volumes[i],state.volumes[i]));
      if(Number.isFinite(value.remainingSeconds))state.remainingSeconds=value.remainingSeconds;
      if(typeof value.preparing==='boolean')state.preparing=value.preparing&&allowed();
      if(typeof value.audioPlaying==='boolean'){state.audioPlaying=value.audioPlaying&&allowed();if(!state.preparing){state.pending=false;clearTimeout(pendingTimeout);}}
      if(Object.hasOwn(value,'error'))state.error=value.error||null;
      // Native parked/background policy owns audio. A locked UI must not echo a pause command.
      if(beforeParked&&!allowed()){state.pending=false;state.preparing=false;state.audioPlaying=false;state.selectingScene=null;activeSliders.clear();clearTimeout(pendingTimeout);clearTimeout(selectionTimeout);}
      const selectionFinished=state.selectingScene===value.sceneId&&value.hasSelectedScene===true;
      if(selectionFinished){state.selectingScene=null;clearTimeout(selectionTimeout);closeSceneChoices();}
      if(beforeLanguage!==state.language)translate();else{if(beforeScene!==state.sceneId)renderScene();else if(selectionFinished||Array.isArray(value.volumes)||beforeParked!==allowed())updateLayers();renderPlayback();updateParking();}
      if(selectionFinished&&beforeScene===state.sceneId)renderSceneChoices();
      maybeChooseFirstScene();
    }catch(_){state.parked=false;state.knownParking=false;updateParking();}
  };
  $('language-button').addEventListener('click',()=>{if(!allowed())return;state.language=state.language==='ko'?'en':'ko';translate();save();});
  $('motion-toggle').addEventListener('click',()=>{if($('motion-toggle').disabled)return;motionEnabled=!motionEnabled;syncMotionControl();});
  motionPreference.addEventListener('change',syncMotionControl);
  $('choose-scene').addEventListener('click',openSceneChoices);
  $('close-scenes').addEventListener('click',()=>closeSceneChoices());
  document.querySelectorAll('[data-minutes]').forEach(button=>button.addEventListener('click',()=>{if(!allowed()||state.selectingScene)return;state.timerMinutes=Number(button.dataset.minutes);state.remainingSeconds=state.timerMinutes*60;native('setTimer',state.timerMinutes);renderPlayback();}));
  $('play-button').addEventListener('click',()=>{
    if(!allowed()||!scene().available||state.pending||state.preparing||state.selectingScene)return;
    if(state.audioPlaying){native('pause');return;}
    state.error=null;state.pending=true;renderPlayback();
    if(!native('play',state.sceneId)){state.pending=false;showToast(t('nativeOnly'));renderPlayback();return;}
    pendingTimeout=setTimeout(()=>{if(state.pending){state.pending=false;state.error=t('error');renderPlayback();}},15000);
  });
  $('mixer-toggle').addEventListener('click',()=>{if(allowed())setMixerOpen(!mixerOpen);});
  $('close-mixer').addEventListener('click',()=>setMixerOpen(false,true));
  document.addEventListener('keydown',event=>{if(event.key==='Escape'&&mixerOpen){event.preventDefault();setMixerOpen(false,true);}});
  $('about-button').addEventListener('click',()=>{if(allowed()){setMixerOpen(false);closeSceneChoices(false);$('about-dialog').showModal();renderCreationDetails();updateAttribution();}});
  $('close-about').addEventListener('click',()=>$('about-dialog').close());
  $('about-dialog').addEventListener('click',event=>{if(event.target===$('about-dialog')){const r=$('about-dialog').getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)$('about-dialog').close();}});
  $('scene-dialog').addEventListener('cancel',event=>{event.preventDefault();closeSceneChoices();});
  document.querySelectorAll('[data-product]').forEach(button=>button.addEventListener('click',()=>{if(!allowed())return;if(!native('openProduct',button.dataset.product))showToast(t('nativeOnly'));}));
  $('report-button').addEventListener('click',()=>{if(allowed()&&!native('reportContent',state.sceneId))showToast(t('nativeOnly'));});
  document.querySelector('.wordmark').addEventListener('click',event=>event.preventDefault());
  window.addEventListener('pointerup',()=>activeSliders.clear());
  window.addEventListener('pointercancel',()=>activeSliders.clear());
  document.addEventListener('visibilitychange',()=>{
    if(document.hidden){
      state.pending=false;activeSliders.clear();clearTimeout(pendingTimeout);
      if(!hasNative()){state.preparing=false;state.audioPlaying=false;}
      renderPlayback();
    }else if(hasNative()){
      // Refresh the service's current state without starting or resuming playback.
      native('ready');
    }
    updateParking();
  });
  window.addEventListener('space:modelLoaded',updateAttribution);
  $('preview-badge').hidden=!preview;
  translate();
  if(hasNative())native('ready');
})();
