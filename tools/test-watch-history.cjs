const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const js = fs.readFileSync('app/src/main/assets/watch-history.js', 'utf8');
const base = 'https://film.spxiaolong.com';
const video = { currentSrc:'blob:test', currentTime:0, duration:100, readyState:1,
  ended:false, seeking:false, seekable:{length:0,start:()=>0,end:()=>100} };
const links = [0,1].map(i => ({textContent:'第'+(i+1)+'集',closest:()=>panel}));
const panel = {querySelectorAll:()=>links};
const tab = {textContent:'线路 A',classList:{contains:()=>true}};
let active = [links[0]];
const document = {
  querySelector:s=>s.includes('video') ? video : {textContent:'测试剧'},
  querySelectorAll:s=>s.includes('active') ? active : s.includes('tab') ? [tab] : [panel]
};
const location = {origin:base,href:base+'/play?id=1&source=A&episode=0'};
const window = {};
vm.runInNewContext(js, {window, location, document, URL});
const tracker=window.__gofilmHistory;
tracker.configure({'1/A/0':42},'');
assert.equal(tracker.sample(),null);
assert.equal(tracker.sample(),null);
assert.equal(video.currentTime,0);
video.seekable.length=1;
assert.equal(tracker.sample(),null);
assert.equal(video.currentTime,42);
assert.equal(tracker.sample().position,42);
active = [links[1]];
video.currentTime=0;
assert.equal(tracker.sample(),null);
assert.equal(tracker.sample(),null);
video.currentTime=23;
assert.equal(tracker.sample().key,'1/A/1');
video.currentTime=100;
video.ended=true;
assert.equal(tracker.sample().completed,true);
location.href=base+'/';
assert.equal(tracker.sample(),null);
location.href=base+'/play?id=1&source=A&episode=0';
active = [links[0]];
video.currentTime=0;
video.ended=false;
tracker.configure({'1/A/0':42},'1/A/0');
assert.equal(tracker.sample(),null);
assert.equal(tracker.sample(),null);
assert.equal(video.currentTime,0);
video.currentTime=3;
assert.equal(tracker.sample().position,3);
console.log('Watch history: restore, seek readiness, episode identity, completion and restart passed.');
