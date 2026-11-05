import * as THREE from './vendor/three.module.js';
import { GLTFLoader } from './vendor/loaders/GLTFLoader.js';
import { createAmbientMotion } from './ambient-motion.js';

// Models always come from the catalog's local GLB. These lighting/backdrop choices
// are app presentation, never substitutes for or provenance of generated assets.
const host=document.getElementById('scene-viewport');
const clamp=(v,a,b)=>Math.min(b,Math.max(a,v));
const reducedMotion=matchMedia('(prefers-reduced-motion: reduce)');
const themes={
  rain:{exposure:1.05,sky:0xffffff,ground:0x69747a,ambient:2.4,key:0xfff4e5,keyPower:2.8,fill:0xdde9f0,fillPower:1.2,shadow:.16,backdrop:'radial-gradient(ellipse at 48% 64%,rgba(68,104,87,.09),transparent 68%)'},
  shore:{exposure:1.06,sky:0xfff6e7,ground:0x657a7d,ambient:2.3,key:0xffd7a5,keyPower:2.65,fill:0xdceeff,fillPower:1.25,shadow:.13,backdrop:'radial-gradient(ellipse at 58% 34%,rgba(198,128,78,.13),transparent 64%),radial-gradient(ellipse at 48% 78%,rgba(75,136,153,.09),transparent 65%)'},
  fireside:{exposure:1.02,sky:0xfff0df,ground:0x746252,ambient:2.1,key:0xffca94,keyPower:2.5,fill:0xdbe5f6,fillPower:1,shadow:.17,backdrop:'radial-gradient(ellipse at 50% 70%,rgba(187,102,52,.15),transparent 64%)'},
  moon:{exposure:1.05,sky:0xe8efff,ground:0x586778,ambient:2.4,key:0xd5e2ff,keyPower:2.5,fill:0xf5e2c5,fillPower:1.1,shadow:.14,backdrop:'radial-gradient(ellipse at 52% 36%,rgba(102,130,189,.12),transparent 68%)'}
};
// Quiet, stationary points behind the moon scene; no per-frame particle work.
const moonStars=[[12,22],[25,12],[39,27],[54,10],[70,20],[88,13],[17,47],[81,43]]
  .map(([x,y])=>`radial-gradient(circle at ${x}% ${y}%,rgba(202,219,248,.24) 0 .7px,transparent 1.5px)`).join(',');
let renderer,world,camera,floor,hemisphere,sun,fill,model=null,frameBounds=null,currentId='',activeSource='',currentVisual={theme:'rain',yaw:0},loadAbort=null,contextLost=false,loadingModel=false,modelFailed=false,generation=0,activity={parked:false,playing:false,visible:true},drag=null,rotationY=0;
let ambient=null,motionEnabled=window.spaceMotionEnabled!==false;
// Preserve the natural viewing angle while centring on each asset, not its export origin.
const viewDirection=new THREE.Vector3(6.2,4.45,7.3).normalize();
const viewRight=new THREE.Vector3().crossVectors(new THREE.Vector3(0,1,0),viewDirection).normalize();
const viewUp=new THREE.Vector3().crossVectors(viewDirection,viewRight).normalize();
function visualFor(config){
  const requested=config.visual?.theme;
  const theme=Object.hasOwn(themes,requested)?requested:Object.hasOwn(themes,config.id)?config.id:'rain';
  const degrees=config.visual?.yawDegrees;
  return {theme,yaw:typeof degrees==='number'&&Number.isFinite(degrees)?clamp(degrees,-360,360)*Math.PI/180:0};
}
function disposeGroup(group){
  if(!group)return;const geometries=new Set(),materials=new Set(),textures=new Set(),images=new Set();
  group.traverse(o=>{if(o.geometry)geometries.add(o.geometry);if(o.material)for(const m of Array.isArray(o.material)?o.material:[o.material])materials.add(m);});
  for(const m of materials)for(const value of Object.values(m))if(value?.isTexture)textures.add(value);
  for(const texture of textures){texture.dispose();if(texture.image)images.add(texture.image);}
  for(const image of images)image.close?.();
  for(const material of materials)material.dispose();for(const geometry of geometries)geometry.dispose();group.removeFromParent();
}
function setViewStatus(status){window.spaceViewStatus=status;window.dispatchEvent(new Event('space:modelLoaded'));}
function render(){if(renderer&&model&&!contextLost&&!loadingModel&&activity.parked&&activity.visible)renderer.render(world,camera);}
// Let the loading text paint before WebGL creation, geometry work or shader compilation.
const afterPaint=()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
function setLighting(visual){
  const theme=themes[visual.theme];
  renderer.toneMappingExposure=theme.exposure;
  hemisphere.color.setHex(theme.sky);hemisphere.groundColor.setHex(theme.ground);hemisphere.intensity=theme.ambient;
  sun.color.setHex(theme.key);sun.intensity=theme.keyPower;
  fill.color.setHex(theme.fill);fill.intensity=theme.fillPower;floor.material.opacity=theme.shadow;
  // Transparent gradients fade into the existing UI; no background geometry is
  // included in model bounds and no synthetic water/fire is passed off as a GLB.
  host.style.backgroundImage=visual.theme==='moon'?moonStars+','+theme.backdrop:theme.backdrop;
}
function clearDrag(){const pointerId=drag?.pointerId;drag=null;host.classList.remove('dragging');if(pointerId!==undefined&&host.hasPointerCapture?.(pointerId))host.releasePointerCapture(pointerId);}
function canRotate(){return !!model&&!loadingModel&&!contextLost&&activity.parked&&activity.visible;}
function setRotation(angle){rotationY=angle;if(model){model.rotation.y=rotationY;render();}}
host.addEventListener('pointerdown',event=>{
  if(!canRotate()||event.button!==0||event.target?.closest?.('button'))return;
  drag={pointerId:event.pointerId,x:event.clientX,y:event.clientY,angle:rotationY,horizontal:false};
  host.setPointerCapture(event.pointerId);host.focus({preventScroll:true});
});
host.addEventListener('pointermove',event=>{
  if(!drag||event.pointerId!==drag.pointerId||!canRotate())return;
  const dx=event.clientX-drag.x,dy=event.clientY-drag.y;
  if(!drag.horizontal){if(Math.abs(dx)<5||Math.abs(dx)<=Math.abs(dy))return;drag.horizontal=true;host.classList.add('dragging');}
  if(event.cancelable)event.preventDefault();setRotation(drag.angle+dx/Math.max(host.clientWidth,1)*Math.PI*1.6);
});
host.addEventListener('pointerup',clearDrag);host.addEventListener('pointercancel',clearDrag);host.addEventListener('lostpointercapture',clearDrag);
host.addEventListener('keydown',event=>{
  if(!canRotate()||event.target?.closest?.('button')||!['ArrowLeft','ArrowRight'].includes(event.key))return;
  event.preventDefault();setRotation(rotationY+(event.key==='ArrowRight' ? .15 : -.15));
});
function measureOrbitFrame(target){
  target.updateWorldMatrix(true,true);
  const pivot=target.getWorldPosition(new THREE.Vector3()),point=new THREE.Vector3();
  const axes=[viewRight,viewUp,viewDirection];
  const horizontal=axes.map(axis=>Math.hypot(axis.x,axis.z));
  const minimum=[Infinity,Infinity,Infinity],maximum=[-Infinity,-Infinity,-Infinity];
  // A vertex sweeps a horizontal circle during drag. Its projected extrema give
  // a fit for every yaw angle, without zoom/centre changes while the user rotates.
  // Measure once after loading; resize only uses these six cached extrema.
  target.traverse(object=>{
    const positions=object.geometry?.getAttribute('position');if(!positions)return;
    for(let i=0;i<positions.count;i++){
      point.fromBufferAttribute(positions,i).applyMatrix4(object.matrixWorld).sub(pivot);
      if(![point.x,point.y,point.z].every(Number.isFinite))continue;
      const radius=Math.hypot(point.x,point.z);
      for(let axis=0;axis<3;axis++){
        const centre=point.y*axes[axis].y,reach=radius*horizontal[axis];
        minimum[axis]=Math.min(minimum[axis],centre-reach);
        maximum[axis]=Math.max(maximum[axis],centre+reach);
      }
    }
  });
  if(!minimum.every(Number.isFinite)||!maximum.every(Number.isFinite))return null;
  const centre=pivot.clone();
  axes.forEach((axis,i)=>centre.addScaledVector(axis,(minimum[i]+maximum[i])/2));
  return {centre,width:Math.max(.001,maximum[0]-minimum[0]),height:Math.max(.001,maximum[1]-minimum[1]),depth:Math.max(.001,maximum[2]-minimum[2])};
}
function resize(draw=true){
  if(!renderer)return;
  ambient?.resize();
  const w=host.clientWidth,h=host.clientHeight;if(!w||!h)return;
  renderer.setSize(w,h,false);
  const aspect=w/h;
  let height=Math.max(5.5,5.3/aspect);
  if(frameBounds){
    // Reserve a small CSS-pixel margin, then fit whichever viewport axis limits
    // the full rotating asset. App slot/media-query size is deliberately irrelevant.
    const inset=Math.min(clamp(Math.min(w,h)*.035,16,28),Math.min(w,h)*.2);
    const scale=Math.min((w-2*inset)/frameBounds.width,(h-2*inset)/frameBounds.height);
    height=h/scale;
    const distance=Math.max(8,frameBounds.depth*2);
    camera.position.copy(frameBounds.centre).addScaledVector(viewDirection,distance);
    camera.lookAt(frameBounds.centre);
    camera.near=Math.max(.1,distance-frameBounds.depth/2-1);
    camera.far=distance+frameBounds.depth/2+4;
  }
  camera.left=-height*aspect/2;camera.right=height*aspect/2;camera.top=height/2;camera.bottom=-height/2;
  camera.updateProjectionMatrix();if(draw!==false)render();
}
// The authored GLB remains static. Only the lightweight atmosphere gets a
// playback-gated loop; WebGL still draws only on interaction/state/size changes.
function updateMotion(){
  render();ambient?.setState({...activity,theme:currentVisual.theme,ready:!!model&&!loadingModel&&!contextLost,enabled:motionEnabled,reducedMotion:reducedMotion.matches});
}
function clearModel(){
  ambient?.setState({ready:false});
  clearDrag();disposeGroup(model);model=null;frameBounds=null;window.loadedSpaceModel=null;
  renderer.domElement.classList.remove('ready');
  if(!contextLost)renderer.clear();
}
async function build(config={}){
  if(!renderer)return;
  if(!config||typeof config!=='object')config={};
  const nextId=typeof config.id==='string'&&/^[a-z][a-z0-9_-]{0,63}$/.test(config.id)?config.id:'';
  const modelUrl=typeof config.modelUrl==='string'?config.modelUrl:'';
  const source=nextId+'|'+modelUrl,visual=visualFor(config);
  // Native catalog replay and language changes retain the current pose/GLB.
  if(source===activeSource){
    if(visual.yaw!==currentVisual.yaw)setRotation(rotationY+visual.yaw-currentVisual.yaw);
    currentVisual=visual;setLighting(visual);resize(false);updateMotion();return;
  }
  loadAbort?.abort();loadAbort=null;
  activeSource=source;currentId=nextId;currentVisual=visual;rotationY=visual.yaw;
  const token=++generation;clearModel();setLighting(visual);modelFailed=false;
  if(!nextId||!modelUrl){loadingModel=false;setViewStatus(contextLost?'lost':'unavailable');return;}
  loadingModel=true;setViewStatus(contextLost?'lost':'loading');
  await afterPaint();
  if(token!==generation||!renderer)return;
  const controller=new AbortController();loadAbort=controller;
  let candidate=null;
  try{
    const url=new URL(modelUrl,location.href);
    if(url.origin!==location.origin||!url.pathname.startsWith('/content/')||!url.pathname.toLowerCase().endsWith('.glb'))throw new Error('Only local content GLB models are accepted');
    // Fetch is abortable. Parsing is not, so stale parse results are disposed below.
    const response=await fetch(url.href,{signal:controller.signal});
    if(!response.ok)throw new Error('Local model could not be read');
    const bytes=await response.arrayBuffer();
    if(token!==generation)return;
    const manager=new THREE.LoadingManager();
    manager.setURLModifier(resource=>{const resolved=new URL(resource,location.href);if(resolved.protocol==='blob:'||resolved.protocol==='data:')return resource;if(resolved.origin!==location.origin||!resolved.pathname.startsWith('/content/'))throw new Error('External model resource blocked');return resolved.href;});
    const loader=new GLTFLoader(manager);loader.setCrossOrigin('anonymous');
    const gltf=await loader.parseAsync(bytes,new URL('.',url).href);candidate=gltf.scene;
    if(token!==generation){disposeGroup(candidate);candidate=null;return;}
    const bounds=new THREE.Box3().setFromObject(candidate),centre=bounds.getCenter(new THREE.Vector3()),size=bounds.getSize(new THREE.Vector3());
    if(bounds.isEmpty()||![size.x,size.y,size.z,centre.x,centre.y,centre.z].every(Number.isFinite)||Math.max(size.x,size.y,size.z)<.000001)throw new Error('Model bounds are not renderable');
    // Put the unmodified scene below a wrapper so authored root transforms survive.
    const normalized=new THREE.Group(),scale=3.3/Math.max(size.x,size.y,size.z);
    normalized.scale.setScalar(scale);normalized.position.set(-centre.x*scale,-bounds.min.y*scale,-centre.z*scale);normalized.add(candidate);
    candidate.traverse(o=>{if(o.isMesh){o.castShadow=true;o.receiveShadow=true;}});
    const nextModel=new THREE.Group();nextModel.position.set(.25,-.35,0);nextModel.rotation.y=rotationY;nextModel.add(normalized);
    const nextFrame=measureOrbitFrame(nextModel);
    if(!nextFrame)throw new Error('Model frame is not renderable');
    model=nextModel;candidate=null;frameBounds=nextFrame;world.add(model);floor.position.y=model.position.y-.02;
    loadingModel=false;resize(false);if(!contextLost)renderer.domElement.classList.add('ready');
    window.loadedSpaceModel=contextLost?null:currentId;setViewStatus(contextLost?'lost':'ready');updateMotion();
  }catch(error){
    disposeGroup(candidate);
    if(token===generation){
      clearModel();loadingModel=false;modelFailed=true;activeSource='';
      setViewStatus(contextLost?'lost':'error');
      console.warn('Local space model unavailable.',error.message);
    }
  }finally{if(loadAbort===controller)loadAbort=null;}
}
async function initialize(){
  setViewStatus('loading');await afterPaint();
  try{
    renderer=new THREE.WebGLRenderer({alpha:true,antialias:true,powerPreference:'low-power'});
    renderer.setPixelRatio(Math.min(devicePixelRatio||1,1.5));renderer.setClearColor(0x000000,0);renderer.shadowMap.enabled=true;renderer.shadowMap.type=THREE.PCFSoftShadowMap;renderer.outputColorSpace=THREE.SRGBColorSpace;renderer.toneMapping=THREE.ACESFilmicToneMapping;host.appendChild(renderer.domElement);
    ambient=createAmbientMotion(host);window.spaceMotionAvailable=ambient.available;
    world=new THREE.Scene();camera=new THREE.OrthographicCamera(-5,5,3,-3,.1,100);camera.position.set(6.2,5.1,7.3);camera.lookAt(0,.65,0);
    hemisphere=new THREE.HemisphereLight(0xffffff,0x69747a,2.4);world.add(hemisphere);
    sun=new THREE.DirectionalLight(0xfff4e5,2.8);sun.position.set(-3,7,5);sun.castShadow=true;sun.shadow.mapSize.set(1024,1024);sun.shadow.camera.left=-5;sun.shadow.camera.right=5;sun.shadow.camera.top=5;sun.shadow.camera.bottom=-5;sun.shadow.normalBias=.025;sun.shadow.bias=-.0001;world.add(sun);
    fill=new THREE.DirectionalLight(0xdde9f0,1.2);fill.position.set(5,3,-4);world.add(fill);
    floor=new THREE.Mesh(new THREE.PlaneGeometry(30,30),new THREE.ShadowMaterial({opacity:.16}));floor.rotation.x=-Math.PI/2;floor.position.y=-.37;floor.receiveShadow=true;world.add(floor);
    activity.parked=document.getElementById('park-lock').hidden;activity.visible=!document.hidden;
    new ResizeObserver(resize).observe(host);
    renderer.domElement.addEventListener('webglcontextlost',event=>{event.preventDefault();contextLost=true;clearDrag();window.loadedSpaceModel=null;renderer.domElement.classList.remove('ready');setViewStatus('lost');updateMotion();});
    renderer.domElement.addEventListener('webglcontextrestored',()=>{contextLost=false;if(model)renderer.domElement.classList.add('ready');window.loadedSpaceModel=model?currentId:null;setViewStatus(model?'ready':loadingModel?'loading':modelFailed?'error':'unavailable');resize(false);updateMotion();});
    await build(window.currentSpaceScene||{});
  }catch(error){
    loadAbort?.abort();disposeGroup(model);model=null;ambient?.dispose();ambient=null;window.spaceMotionAvailable=false;renderer?.dispose();renderer=null;
    window.loadedSpaceModel=null;setViewStatus('unavailable');host.classList.add('render-unavailable');console.warn('3D display unavailable:',error.message);
  }
}
window.addEventListener('space:scene',event=>{build(event.detail);});
window.addEventListener('space:activity',event=>{const prior=activity;activity={...activity,...event.detail};if(!activity.parked||!activity.visible)clearDrag();if(activity.parked!==prior.parked||activity.visible!==prior.visible||activity.playing!==prior.playing)updateMotion();});
window.addEventListener('space:motion',event=>{motionEnabled=event.detail?.enabled===true;updateMotion();});
reducedMotion.addEventListener('change',updateMotion);
initialize();
