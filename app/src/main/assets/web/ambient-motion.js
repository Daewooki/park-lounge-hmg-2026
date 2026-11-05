// App-authored atmosphere layered over the unchanged, locally generated GLB.
// One small 2D surface keeps the WebGL model on its existing on-demand renderer.
export function createAmbientMotion(host, options={}) {
  const canvas=options.canvas||document.createElement('canvas');
  canvas.className='ambient-layer';canvas.setAttribute('aria-hidden','true');
  canvas.hidden=true;host.appendChild(canvas);
  let context;
  try {context=canvas.getContext('2d',{alpha:true});}catch(_){context=null;}
  const requestFrame=options.requestFrame||requestAnimationFrame;
  const cancelFrame=options.cancelFrame||cancelAnimationFrame;
  const pixelRatio=Math.min(options.pixelRatio||globalThis.devicePixelRatio||1,1.25);
  const frameDuration=1000/24;
  const state={theme:'rain',parked:false,playing:false,visible:true,ready:false,enabled:true,reducedMotion:false};
  let width=0,height=0,frame=null,lastFrame=null,elapsed=0,disposed=false;
  // Deterministic seeds stay stable through resize and never allocate per frame.
  const seeds=Array.from({length:56},(_,i)=>({
    x:((i*37+13)%101)/101,y:((i*67+29)%103)/103,
    phase:i*2.399963,speed:.15+(i%5)*.025,size:.7+(i%4)*.25
  }));
  function active(){return !!context&&!disposed&&width>0&&height>0&&state.ready&&state.parked&&state.playing&&state.visible&&state.enabled&&!state.reducedMotion;}
  function clear(){if(context)context.clearRect(0,0,width,height);}
  function glow(x,y,radius,color,alpha){
    const gradient=context.createRadialGradient(x,y,0,x,y,radius);
    gradient.addColorStop(0,`rgba(${color},${alpha})`);gradient.addColorStop(1,`rgba(${color},0)`);
    context.fillStyle=gradient;context.fillRect(0,0,width,height);
  }
  function drawRain(t){
    context.lineWidth=.8;context.lineCap='round';
    for(const s of seeds){
      const y=((s.y+t*s.speed)%1)*(height+28)-14;
      const x=s.x*width-Math.sin(t*.16+s.phase)*5;
      const alpha=.12+(s.size-.7)*.08;
      context.strokeStyle=`rgba(168,204,215,${alpha})`;
      context.beginPath();context.moveTo(x,y);context.lineTo(x-2,y+8+s.size*5);context.stroke();
    }
    // A few soft rings give rain a resting surface without covering the model.
    for(let i=0;i<5;i++){
      const phase=(t*.2+i*.21)%1,s=seeds[i*7];
      context.strokeStyle=`rgba(155,198,204,${Math.sin(phase*Math.PI)*.14})`;
      context.beginPath();context.ellipse(width*(.1+s.x*.8),height*(.82+s.y*.12),6+phase*14,1+phase*3,0,0,Math.PI*2);context.stroke();
    }
  }
  function drawShore(t){
    glow(width*.63,height*.28,Math.max(width,height)*.44,'238,172,109',.045+Math.sin(t*.34)*.012);
    context.lineWidth=1;
    for(let i=0;i<8;i++){
      const phase=(t*.075+i/8)%1,y=height*(.67+phase*.3);
      const span=width*(.12+phase*.27),x=width*.5+Math.sin(t*.25+i)*width*.04;
      context.strokeStyle=`rgba(160,219,225,${Math.sin(phase*Math.PI)*.19})`;
      context.beginPath();context.ellipse(x,y,span,3+phase*10,0,.12*Math.PI,.88*Math.PI);context.stroke();
    }
  }
  function drawFireside(t){
    // Slow, low amplitude warmth instead of flashing firelight.
    const warmth=.065+Math.sin(t*.7)*.012+Math.sin(t*.31)*.006;
    glow(width*.5,height*.67,Math.min(width,height)*.42,'235,133,57',warmth);
    for(let i=0;i<12;i++){
      const s=seeds[i],phase=(s.y+t*(.045+(i%3)*.008))%1;
      const alpha=Math.sin(phase*Math.PI)*.3;
      const x=width*(.39+s.x*.22)+Math.sin(t*.4+s.phase)*6,y=height*(.8-phase*.46);
      context.fillStyle=`rgba(248,179,98,${alpha})`;context.beginPath();context.arc(x,y,s.size*.75,0,Math.PI*2);context.fill();
    }
  }
  function drawMoon(t){
    glow(width*.48,height*.36,Math.max(width,height)*.48,'110,151,218',.035+Math.sin(t*.19)*.008);
    for(let i=0;i<24;i++){
      const s=seeds[i],x=((s.x+t*.0015)%1)*width,y=height*(.06+s.y*.63);
      // The centre is kept clear for the lunar lounge and its Earth window.
      if(x>width*.26&&x<width*.77&&y>height*.26)continue;
      const alpha=.18+(1+Math.sin(t*.38+s.phase))*.09;
      context.fillStyle=`rgba(203,223,252,${alpha})`;context.beginPath();context.arc(x,y,s.size*.8,0,Math.PI*2);context.fill();
    }
  }
  const painters={rain:drawRain,shore:drawShore,fireside:drawFireside,moon:drawMoon};
  function draw(){
    clear();
    const painter=painters[state.theme]||drawRain;
    painter(elapsed);
  }
  function tick(timestamp){
    frame=null;if(!active()){stop();return;}
    if(lastFrame===null){lastFrame=timestamp;draw();}
    else if(timestamp-lastFrame>=frameDuration){
      // No leap after a delayed callback; hidden state is also gated above.
      elapsed+=Math.min((timestamp-lastFrame)/1000,.1);lastFrame=timestamp;draw();
    }
    frame=requestFrame(tick);
  }
  function stop(){if(frame!==null)cancelFrame(frame);frame=null;lastFrame=null;canvas.hidden=true;clear();}
  function sync(){
    if(!active()){stop();return;}
    canvas.hidden=false;
    if(frame===null){lastFrame=null;frame=requestFrame(tick);}
  }
  function resize(){
    width=Math.max(0,host.clientWidth||0);height=Math.max(0,host.clientHeight||0);
    if(context){canvas.width=Math.max(1,Math.ceil(width*pixelRatio));canvas.height=Math.max(1,Math.ceil(height*pixelRatio));context.setTransform(pixelRatio,0,0,pixelRatio,0,0);}
    sync();
  }
  function setState(next={}){
    if(disposed)return;
    if(next.theme&&next.theme!==state.theme){stop();elapsed=0;}
    Object.assign(state,next);sync();
  }
  function dispose(){if(disposed)return;stop();disposed=true;canvas.remove();}
  resize();
  return {available:!!context,setState,resize,dispose};
}
