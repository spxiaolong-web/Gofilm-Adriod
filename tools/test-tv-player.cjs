const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const script = fs.readFileSync('app/src/main/assets/tv-player.js', 'utf8');

function setup(origin = 'film.spxiaolong.com', pathname = '/play') {
  let currentVideo = null;
  const nodes = {};
  const player = {id:'original-player',setAttribute(key,value){this[key]=value;}};
  function makeVideo() {
    const listeners = {};
    return {paused:true, ended:false, readyState:2, currentTime:25, duration:100, controls:true,
      error:null, seekable:{length:0}, playCalls:0,
      closest() {return player;}, parentElement:player,
      addEventListener(name, callback) {listeners[name] = callback;},
      play() {this.playCalls++; this.paused=false; return Promise.resolve();},
      pause() {this.paused=true;}, listeners};
  }
  const context = vm.createContext({window:{}, location:{protocol:'https:',hostname:origin,pathname},
    document:{documentElement:{},head:{appendChild(node){nodes[node.id]=node;}},
      querySelector(){return currentVideo;},getElementById(id){return nodes[id];},createElement(){return {}; }},
    MutationObserver:class {constructor(callback){this.callback=callback;}observe(){} }
  });
  return {context, nodes, player, makeVideo, setVideo(video){currentVideo=video;},
    run(){vm.runInContext(script, context);return context.window.__tvPlayer;}};
}

(async () => {
  const fixture=setup();
  let api=fixture.run();
  assert.equal(api.state().ready,false);
  assert.equal(setup().run().command('toggle'),false);
  const video=fixture.makeVideo();fixture.setVideo(video);
  assert.equal(api.state().ready,true);
  assert.equal(video.playCalls,1); // explicit episode selection starts when ready
  assert.equal(video.controls,false);
  assert.equal(fixture.player.id,'original-player');
  assert.equal(fixture.player['data-tv-video-root'],'true');
  assert.ok(fixture.nodes['tv-player-style'].textContent.includes('object-fit:contain'));
  api.command('pause');assert.equal(video.paused,true);
  video.listeners.canplay();assert.equal(video.paused,true); // buffering must not undo a manual pause
  api.command('toggle');assert.equal(video.paused,false);
  api.command('forward');assert.equal(video.currentTime,35);
  video.currentTime=98;api.command('forward');assert.equal(video.currentTime,99.9);
  video.currentTime=4;api.command('rewind');assert.equal(video.currentTime,0);
  video.duration=Infinity;video.seekable={length:0};assert.equal(api.command('forward'),false);
  video.seekable={length:1,start(){return 50;},end(){return 90;}};
  video.currentTime=52;api.command('rewind');assert.equal(video.currentTime,50);
  video.currentTime=88;api.command('forward');assert.equal(video.currentTime,89.9);
  video.play=()=>Promise.reject(new Error('unsupported'));
  api.command('play');await Promise.resolve();await Promise.resolve();
  assert.ok(api.state().error.includes('暂时无法播放'));
  video.error={code:3};video.play=()=>Promise.resolve();api.command('play');
  assert.ok(api.state().error.includes('（3）'));
  assert.equal(api.command('unknown'),false);
  assert.equal(fixture.run(),api); // injection stays idempotent
  assert.equal(setup('example.com').run(),undefined);
  assert.equal(setup('film.spxiaolong.com','/index').run(),undefined);
  const background=setup();const suspended=background.run();
  suspended.command('pause');const late=background.makeVideo();background.setVideo(late);
  suspended.state();late.listeners.canplay();assert.equal(late.playCalls,0);
  console.log('TV player control regression checks passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
