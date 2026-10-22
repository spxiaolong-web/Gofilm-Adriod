(function () {
  'use strict';
  if (location.protocol !== 'https:' || location.hostname !== 'film.spxiaolong.com' || location.pathname !== '/play') return;
  if (window.__tvPlayer) return;
  var video = null, manual = false, autoAttempted = false, lastError = '';

  function play() {
    if (!video) return false;
    lastError = '';
    try {
      var promise = video.play();
      if (promise && promise.catch) promise.catch(function () { lastError = '暂时无法播放，可重试或返回选集更换线路'; });
      return true;
    } catch (error) { lastError = '播放器暂未准备好'; return false; }
  }

  function autoStart() {
    if (!manual && !autoAttempted && video && video.readyState >= 2) {
      autoAttempted = true;
      play();
    }
  }

  function attach() {
    var found = document.querySelector('video');
    if (!found || found === video) return;
    video = found;
    autoAttempted = false;
    var player = video.closest('.xgplayer') || video.parentElement;
    if (!player) return;
    player.setAttribute('data-tv-video-root', 'true');
    if (!document.getElementById('tv-player-style')) {
      var style = document.createElement('style');
      style.id = 'tv-player-style';
      style.textContent = 'html,body{background:#000!important;overflow:hidden!important;margin:0!important;}' +
        'body *{visibility:hidden!important;}' +
        '[data-tv-video-root],[data-tv-video-root] *{visibility:visible!important;}' +
        '[data-tv-video-root]{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;' +
        'max-width:none!important;min-height:0!important;margin:0!important;padding:0!important;' +
        'z-index:2147480000!important;background:#000!important;}' +
        '[data-tv-video-root] video{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;object-fit:contain!important;}' +
        '[data-tv-video-root] .xgplayer-controls,[data-tv-video-root] .xgplayer-start,[data-tv-video-root] .xgplayer-poster{display:none!important;}';
      document.head.appendChild(style);
    }
    video.controls = false;
    video.addEventListener('canplay', autoStart);
    autoStart();
  }

  function seek(delta) {
    if (!video) return false;
    var lower = 0, upper = video.duration;
    if (!Number.isFinite(upper)) {
      if (!video.seekable || !video.seekable.length) return false;
      lower = video.seekable.start(0);
      upper = video.seekable.end(video.seekable.length - 1);
    }
    if (upper <= lower) return false;
    try {
      video.currentTime = Math.max(lower, Math.min(Math.max(lower, upper - 0.1), (video.currentTime || 0) + delta));
      return true;
    } catch (error) { return false; }
  }

  window.__tvPlayer = {
    command: function (action) {
      manual = true;
      attach();
      if (!video) return false;
      if (action === 'toggle') { if (video.paused) return play(); video.pause(); return true; }
      if (action === 'play') return play();
      if (action === 'pause') { video.pause(); return true; }
      if (action === 'forward') return seek(10);
      if (action === 'rewind') return seek(-10);
      return false;
    },
    state: function () {
      attach();
      return { ready: !!video, paused: !video || video.paused, ended: !!video && video.ended,
        loading: !!video && video.readyState < 2,
        current: video && Number.isFinite(video.currentTime) ? video.currentTime : 0,
        duration: video && Number.isFinite(video.duration) ? video.duration : 0,
        error: lastError || (video && video.error ? '视频加载失败（' + video.error.code + '），可返回选集更换线路' : '') };
    }
  };
  new MutationObserver(attach).observe(document.documentElement, {childList:true, subtree:true});
  attach();
})();
