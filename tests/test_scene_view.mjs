// Host-only renderer policy tests. Meshes below are fixtures, never app assets.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import * as Three from '../app/src/main/assets/web/vendor/three.module.js';
import {createAmbientMotion} from '../app/src/main/assets/web/ambient-motion.js';
const source=fs.readFileSync(new URL('../app/src/main/assets/web/scene-view.js',import.meta.url),'utf8');
const events=new Map(),canvasEvents=new Map(),hostEvents=new Map();
const motionFrames=new Map();let nextMotionFrame=0,motionClears=0,motionStrokes=0;
const motionContext={clearRect(){motionClears++;},setTransform(){},createRadialGradient(){return {addColorStop(){}};},fillRect(){},beginPath(){},moveTo(){},lineTo(){},ellipse(){},arc(){},stroke(){motionStrokes++;},fill(){motionStrokes++;}};
const motionCanvas={hidden:true,setAttribute(){},getContext:()=>motionContext,remove(){this.removed=true;}};
const reducedMotionSignal={matches:false,addEventListener(_,listener){this.change=listener;}};
function motionFrame(timestamp){const callbacks=[...motionFrames.values()];motionFrames.clear();for(const callback of callbacks)callback(timestamp);}
function makeMotion(target){return createAmbientMotion(target,{canvas:motionCanvas,pixelRatio:2,requestFrame(callback){motionFrames.set(++nextMotionFrame,callback);return nextMotionFrame;},cancelFrame(id){motionFrames.delete(id);}});}
function classList(){const values=new Set();return {add:x=>values.add(x),remove:x=>values.delete(x),contains:x=>values.has(x)};}
let pointerCaptures=0,hostFocuses=0;
const host={clientWidth:564,clientHeight:383,style:{},classList:classList(),addEventListener:(n,f)=>hostEvents.set(n,f),appendChild(){},setPointerCapture(){pointerCaptures++;},releasePointerCapture(){},hasPointerCapture:()=>false,focus(){hostFocuses++;}};
class Renderer {constructor(){this.domElement={classList:classList(),addEventListener:(n,f)=>canvasEvents.set(n,f)};this.shadowMap={};this.draws=0;this.clears=0;}setPixelRatio(){}setClearColor(){}setSize(){}render(){this.draws++;}clear(){this.clears++;}dispose(){}}
const requests=[],parses=[],managers=[];
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};}
function fetchLocal(url,{signal}){const d=deferred();let completed=false;const request={url,signal,resolve(){completed=true;d.resolve({ok:true,arrayBuffer:async()=>new ArrayBuffer(1)});}};requests.push(request);signal.addEventListener('abort',()=>{if(!completed)d.reject(new Error('Aborted'));});return d.promise;}
class Loader {constructor(manager){managers.push(manager);}setCrossOrigin(){}parseAsync(){const d=deferred();parses.push(d);return d.promise;}}
const win={currentSpaceScene:{},addEventListener:(n,f)=>events.set(n,f),dispatchEvent(){}};
const context=vm.createContext({THREE:{...Three,WebGLRenderer:Renderer},GLTFLoader:Loader,createAmbientMotion:makeMotion,document:{hidden:false,getElementById:id=>id==='scene-viewport'?host:{hidden:true}},window:win,location:new URL('https://appassets.androidplatform.net/web/index.html'),matchMedia:()=>reducedMotionSignal,ResizeObserver:class{observe(){}},requestAnimationFrame:f=>queueMicrotask(()=>f(0)),devicePixelRatio:1.375,AbortController,URL,fetch:fetchLocal,Event,console:{warn(){}},Math,Number,Set,Promise});
vm.runInContext(source.replace(/^import .*;\n/gm,'').replace(/initialize\(\);\s*$/,'')+'\nglobalThis.test={initialize,build,resize,setRotation,measureOrbitFrame,state:()=>({model,camera,frameBounds,loadingModel,activeSource,currentId,rotationY,world,renderer})};',context);
const t=context.test,flush=async()=>{for(let i=0;i<12;i++)await Promise.resolve();};
function fixture(){const stats={geometry:0,material:0,texture:0,image:0};const root=new Three.Group();root.position.set(.3,-.2,.7);root.rotation.y=.2;root.scale.set(1.2,.7,.9);const geometry=new Three.BoxGeometry(2,3,1);const texture=new Three.Texture({close(){stats.image++;}});const material=new Three.MeshStandardMaterial({map:texture});geometry.addEventListener('dispose',()=>stats.geometry++);material.addEventListener('dispose',()=>stats.material++);texture.addEventListener('dispose',()=>stats.texture++);root.add(new Three.Mesh(geometry,material));return {scene:root,stats};}
async function finishLoad(promise,requestIndex,fixtureValue){requests[requestIndex].resolve();await flush();parses.at(-1).resolve(fixtureValue);await promise;}
await t.initialize();assert.equal(win.spaceViewStatus,'unavailable');assert.equal(requests.length,0);
const rain={id:'rain',modelUrl:'/content/rain/model.glb'},shore={id:'shore',modelUrl:'/content/shore/model.glb'};
const a=t.build(rain);await flush();assert.equal(t.state().loadingModel,true);
const b=t.build(shore);await flush();assert.equal(requests[0].signal.aborted,true);assert.equal(win.loadedSpaceModel,null);assert.equal(t.state().renderer.domElement.classList.contains('ready'),false);
const shoreAsset=fixture();await finishLoad(b,1,shoreAsset);await a;
assert.equal(win.loadedSpaceModel,'shore');assert.equal(win.spaceViewStatus,'ready');assert.equal(shoreAsset.stats.geometry,0);assert.match(host.style.backgroundImage,/198,128,78/);
// The real atmosphere controller draws only for active, safe playback and never
// turns the static GLB into a continuous WebGL render loop.
assert.equal(motionFrames.size,0);assert.equal(motionCanvas.hidden,true);
events.get('space:activity')({detail:{parked:true,playing:true,visible:true}});
assert.equal(motionFrames.size,1);assert.equal(motionCanvas.hidden,false);
const webglBeforeMotion=t.state().renderer.draws,clearsBeforeMotion=motionClears;
for(let ms=0;ms<=1000;ms+=1000/60)motionFrame(ms);
assert(motionClears-clearsBeforeMotion<=25,'No more than 24 frames per second plus initial draw');
assert(motionClears-clearsBeforeMotion>=15);assert(motionStrokes>0);
assert.equal(t.state().renderer.draws,webglBeforeMotion,'Atmosphere must not redraw GLB each frame');
assert.equal(motionCanvas.width,Math.ceil(host.clientWidth*1.25),'2D DPR has a bounded backing buffer');
for(const inactive of [{playing:false},{visible:false},{parked:false}]){
  events.get('space:activity')({detail:inactive});assert.equal(motionFrames.size,0);assert.equal(motionCanvas.hidden,true);
  const stoppedClears=motionClears;motionFrame(2000);assert.equal(motionClears,stoppedClears);
  events.get('space:activity')({detail:{parked:true,playing:true,visible:true}});assert.equal(motionFrames.size,1);
}
events.get('space:motion')({detail:{enabled:false}});assert.equal(motionFrames.size,0);
events.get('space:motion')({detail:{enabled:true}});assert.equal(motionFrames.size,1);
reducedMotionSignal.matches=true;reducedMotionSignal.change();assert.equal(motionFrames.size,0);
reducedMotionSignal.matches=false;reducedMotionSignal.change();assert.equal(motionFrames.size,1);
// A motion button (or its SVG/label child) must receive its own tap/keyboard
// activation. The surrounding drag surface cannot capture or focus that input.
const beforeButtonAngle=t.state().rotationY;
const buttonTarget={closest:selector=>selector==='button'?{tagName:'BUTTON'}:null};
hostEvents.get('pointerdown')({button:0,pointerId:42,clientX:20,clientY:20,target:buttonTarget});
assert.equal(pointerCaptures,0);assert.equal(hostFocuses,0);
hostEvents.get('pointermove')({pointerId:42,clientX:140,clientY:20,cancelable:true,preventDefault(){throw Error('Button tap became a scene drag');}});
hostEvents.get('keydown')({key:'ArrowRight',target:buttonTarget,preventDefault(){throw Error('Button key was intercepted');}});
assert.equal(t.state().rotationY,beforeButtonAngle);
// Normal viewport dragging remains usable after the guarded button interaction.
hostEvents.get('pointerdown')({button:0,pointerId:43,clientX:20,clientY:20,target:{closest:()=>null}});
assert.equal(pointerCaptures,1);assert.equal(hostFocuses,1);
hostEvents.get('pointermove')({pointerId:43,clientX:140,clientY:20,cancelable:true,preventDefault(){}});
assert.notEqual(t.state().rotationY,beforeButtonAngle);hostEvents.get('pointerup')();
const loaded=t.state().model;t.setRotation(.4);const count=requests.length;await t.build(shore);assert.equal(t.state().model,loaded);assert.equal(requests.length,count);assert.equal(t.state().rotationY,.4);
await t.build({...shore,visual:{theme:'moon',yawDegrees:90}});assert.equal(t.state().model,loaded);assert.equal(requests.length,count);assert(Math.abs(t.state().rotationY-(.4+Math.PI/2))<1e-12);assert.match(host.style.backgroundImage,/circle at/);
// Resource managers are per load and cannot fetch textures from remote sites.
assert.throws(()=>managers[0].resolveURL('https://example.invalid/image.png'),/External model/);
assert.equal(managers[0].resolveURL('blob:https://appassets.androidplatform.net/image'),'blob:https://appassets.androidplatform.net/image');
const c=t.build({id:'fireside',modelUrl:'/content/fireside/model.glb'});await flush();requests[2].resolve();await flush();const staleParse=parses.at(-1);
assert.equal(motionFrames.size,0,'Changing a scene stops animation while its model loads');
assert.equal(shoreAsset.stats.geometry,1);assert.equal(shoreAsset.stats.material,1);assert.equal(shoreAsset.stats.texture,1);assert.equal(shoreAsset.stats.image,1);
const d=t.build({id:'moon',modelUrl:'/content/moon/model.glb'});await flush();const moonAsset=fixture();await finishLoad(d,3,moonAsset);
const staleAsset=fixture();staleParse.resolve(staleAsset);await c;
assert.equal(staleAsset.stats.geometry,1);assert.equal(staleAsset.stats.texture,1);assert.equal(staleAsset.stats.image,1);assert.equal(win.loadedSpaceModel,'moon');assert.equal(t.state().world.children.filter(o=>o.isGroup).length,1);
// Authored root transforms survive normalization; camera fit never edits the GLB.
assert.deepEqual(moonAsset.scene.position.toArray(),[.3,-.2,.7]);assert.deepEqual(moonAsset.scene.scale.toArray(),[1.2,.7,.9]);
let projected=0;
for(const [width,height] of [[564,383],[798,461],[320,500],[1000,600]]){host.clientWidth=width;host.clientHeight=height;t.resize(false);const {model,camera}=t.state();camera.updateMatrixWorld();for(let degrees=0;degrees<360;degrees+=5){model.rotation.y=degrees*Math.PI/180;model.updateMatrixWorld(true);model.traverse(o=>{const positions=o.geometry?.getAttribute('position');if(!positions)return;for(let i=0;i<positions.count;i++){const point=new Three.Vector3().fromBufferAttribute(positions,i).applyMatrix4(o.matrixWorld).project(camera);assert(Math.abs(point.x)<1&&Math.abs(point.y)<1&&Math.abs(point.z)<1);projected++;}});}}
const draws=t.state().renderer.draws;host.clientWidth=0;t.resize();assert.equal(t.state().renderer.draws,draws);host.clientWidth=564;
// Vehicle lock/background prevent interaction and drawing; recovery redraws once.
events.get('space:activity')({detail:{parked:false}});const lockedDraws=t.state().renderer.draws;hostEvents.get('keydown')({key:'ArrowRight',preventDefault(){throw new Error('Locked interaction');}});t.resize();assert.equal(t.state().renderer.draws,lockedDraws);
events.get('space:activity')({detail:{parked:true,visible:false}});t.resize();assert.equal(t.state().renderer.draws,lockedDraws);
events.get('space:activity')({detail:{visible:true}});assert(t.state().renderer.draws>lockedDraws);
canvasEvents.get('webglcontextlost')({preventDefault(){}});assert.equal(win.loadedSpaceModel,null);assert.equal(win.spaceViewStatus,'lost');assert.equal(motionFrames.size,0);canvasEvents.get('webglcontextrestored')();assert.equal(win.loadedSpaceModel,'moon');assert.equal(win.spaceViewStatus,'ready');assert.equal(motionFrames.size,1);
const beforeInvalid=requests.length;await t.build({id:'shore',modelUrl:'https://example.invalid/model.glb'});assert.equal(requests.length,beforeInvalid);assert.equal(win.spaceViewStatus,'error');assert.equal(win.loadedSpaceModel,null);assert.equal(moonAsset.stats.geometry,1);
await t.build({id:'future_space'});assert.equal(win.spaceViewStatus,'unavailable');assert.equal(t.state().model,null);assert.equal(requests.length,beforeInvalid);assert.equal(t.state().renderer.domElement.classList.contains('ready'),false);
assert.equal(motionFrames.size,0,'Unavailable models cannot leave a motion loop alive');
// Standalone resource disposal and graceful 2D fallback cover WebGL startup
// failures without adding animation objects to the generated scene graph.
const disposalCanvas={...motionCanvas,removed:false};const disposal=createAmbientMotion(host,{canvas:disposalCanvas,requestFrame:f=>{motionFrames.set(++nextMotionFrame,f);return nextMotionFrame;},cancelFrame:id=>motionFrames.delete(id)});
disposal.setState({parked:true,playing:true,ready:true});assert.equal(motionFrames.size,1);
disposal.dispose();disposal.dispose();assert.equal(motionFrames.size,0);assert.equal(disposalCanvas.removed,true);
const unavailable=createAmbientMotion(host,{canvas:{...motionCanvas,getContext:()=>null},requestFrame(){throw Error('Unavailable canvas requested frame');},cancelFrame(){}});
unavailable.setState({parked:true,playing:true,ready:true});assert.equal(unavailable.available,false);unavailable.dispose();
// Measure every packaged model and optional scene=path candidates, without
// decoding textures or rendering. Candidate paths never modify the catalog.
const catalog=JSON.parse(fs.readFileSync(new URL('../app/src/main/assets/catalog.json',import.meta.url),'utf8'));
const assets=catalog.scenes.filter(scene=>scene.modelUrl).map(config=>({config,path:new URL('../app/src/main/assets'+config.modelUrl,import.meta.url)}));
assert(assets.some(asset=>asset.config.id==='rain'),'The existing rain model remains a production framing fixture');
for(const argument of process.argv.slice(2)){
  const split=argument.indexOf('=');
  assert(split>0,'Candidate argument must be scene=path.glb');
  const id=argument.slice(0,split),path=argument.slice(split+1);
  assert(/^[a-z][a-z0-9_-]{0,63}$/.test(id)&&path.endsWith('.glb'),'Invalid candidate model');
  assets.push({config:{id,modelUrl:`/content/${id}/frame-candidate.glb`},path});
}
const actualResults=[];
for(const {config:actual,path} of assets){
const bytes=fs.readFileSync(path);
const jsonLength=bytes.readUInt32LE(12),gltf=JSON.parse(bytes.subarray(20,20+jsonLength)),binary=bytes.subarray(28+jsonLength);
const meshes=gltf.meshes.map(mesh=>{const group=new Three.Group();for(const primitive of mesh.primitives){const accessor=gltf.accessors[primitive.attributes.POSITION],view=gltf.bufferViews[accessor.bufferView];assert.equal(accessor.componentType,5126);assert.equal(accessor.type,'VEC3');const positions=new Float32Array(accessor.count*3),offset=(view.byteOffset||0)+(accessor.byteOffset||0),stride=view.byteStride||12;for(let i=0;i<accessor.count;i++)for(let j=0;j<3;j++)positions[i*3+j]=binary.readFloatLE(offset+i*stride+j*4);const geometry=new Three.BufferGeometry();geometry.setAttribute('position',new Three.BufferAttribute(positions,3));group.add(new Three.Mesh(geometry,new Three.MeshBasicMaterial()));}return group;});
const nodes=gltf.nodes.map(node=>{const group=new Three.Group();if(node.matrix){group.matrix.fromArray(node.matrix);group.matrix.decompose(group.position,group.quaternion,group.scale);}else{if(node.translation)group.position.fromArray(node.translation);if(node.rotation)group.quaternion.fromArray(node.rotation);if(node.scale)group.scale.fromArray(node.scale);}if(node.mesh!==undefined)group.add(meshes[node.mesh].clone(true));return group;});
gltf.nodes.forEach((node,i)=>{for(const child of node.children||[])nodes[i].add(nodes[child]);});
const actualScene=new Three.Group();for(const node of gltf.scenes[gltf.scene||0].nodes)actualScene.add(nodes[node]);
const requestIndex=requests.length,actualLoad=t.build(actual);await flush();await finishLoad(actualLoad,requestIndex,{scene:actualScene});
let actualChecks=0;
for(const [width,height] of [[564,383],[798,461],[320,500],[1000,600]]){host.clientWidth=width;host.clientHeight=height;t.resize(false);const {model,camera}=t.state();camera.updateMatrixWorld();for(let degrees=0;degrees<360;degrees+=5){model.rotation.y=degrees*Math.PI/180;model.updateMatrixWorld(true);model.traverse(o=>{const positions=o.geometry?.getAttribute('position');if(!positions)return;const point=new Three.Vector3();for(let i=0;i<positions.count;i++){point.fromBufferAttribute(positions,i).applyMatrix4(o.matrixWorld).project(camera);assert(Math.abs(point.x)<1&&Math.abs(point.y)<1&&Math.abs(point.z)<1,`${actual.id}: clipped at ${width}x${height}, yaw ${degrees}`);actualChecks++;}});}}
actualResults.push(`${actual.id}=${actualChecks}`);
}
console.log(`PASS: bounded atmosphere cadence, no per-frame GLB render, pause/lock/visibility/reduced-motion/context gating and disposal; abort/stale-load disposal, same-source pose, four themes, source transforms, resource restrictions, missing-model state; ${projected} fixture projections; real model projection checks ${actualResults.join(', ')}.`);
