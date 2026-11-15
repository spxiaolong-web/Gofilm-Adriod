(function () {
  'use strict';
  if (location.origin !== 'https://film.spxiaolong.com' || window.__gofilmHistory) return;
  var saved = {}, skip = '', session, route, sources, loadingSources;
  var validSource = /^[A-Za-z0-9_-]{1,80}$/;
  function identity() {
    var url = new URL(location.href);
    if (url.pathname !== '/play') return null;
    var id = url.searchParams.get('id') || '', source = url.searchParams.get('source') || '';
    var index = url.searchParams.get('episode') || '0';
    if (!/^[1-9][0-9]{0,15}$/.test(id) || !validSource.test(source) || !/^[0-9]{1,7}$/.test(index)) return null;
    var routeKey = id + '/' + source + '/' + Number(index);
    if (!route || route.key !== routeKey) {
      route = {key:routeKey, tab:-1};
      sources = null;
      loadingSources = false;
      session = null;
    }
    var activeLinks = document.querySelectorAll('.play-list-item .play-link-active');
    if (activeLinks.length > 1) return null;
    var active = activeLinks[0];
    var tabs = Array.from(document.querySelectorAll('.play-tab-item'));
    var panels = Array.from(document.querySelectorAll('.play-list-item'));
    if (!active && panels.length) return null;
    // Changing the visible source tab alone does not change the playing video.
    if (active && panels.length && tabs.length === panels.length) {
      var panel = active.closest('.play-list-item');
      var tabIndex = panels.indexOf(panel);
      var actual = Array.from(panel.querySelectorAll('.play-link')).indexOf(active);
      if (tabIndex < 0 || actual < 0) return null;
      if (route.tab < 0 && Number(index) === actual && tabs[tabIndex].classList.contains('tab-active')) route.tab = tabIndex;
      if (tabIndex !== route.tab) {
        if (!sources && !loadingSources) {
          loadingSources = true;
          var requestRoute = route;
          fetch('/api/filmDetail?id=' + id, {credentials:'same-origin', cache:'no-store'})
            .then(function (response) { return response.json(); })
            .then(function (data) {
              if (route === requestRoute && data.code === 0 && data.data && data.data.detail)
                sources = data.data.detail.list;
            }).catch(function () {});
        }
        var match = sources && sources[tabIndex];
        if (!match || !validSource.test(match.id) || String(match.name).trim() !== tabs[tabIndex].textContent.trim()
            || !Array.isArray(match.linkList) || !match.linkList[actual]
            || String(match.linkList[actual].episode).trim() !== active.textContent.trim()) return null;
        source = String(match.id);
      }
      index = actual;
    }
    var title = document.querySelector('.current_play_title');
    return {key:id + '/' + source + '/' + Number(index), title:title ? title.textContent.trim().slice(0,160) : ''};
  }
  function sample() {
    var item = identity();
    var video = document.querySelector('.player_container video') || document.querySelector('.xgplayer video') || document.querySelector('video');
    if (!item || !video) { session = null; return null; }
    var media = video.currentSrc || video.src || '';
    if (!session || session.key !== item.key || session.video !== video || session.media !== media) {
      // Let a newly selected episode finish replacing the previous video source.
      session = {key:item.key,video:video,media:media,restored:false};
      return null;
    }
    var time = video.currentTime, duration = video.duration;
    if (video.readyState < 1 || !Number.isFinite(duration) || duration < 3 || !Number.isFinite(time) || video.seeking) return null;
    if (!session.restored) {
      var resume = Number(saved[item.key]) || 0;
      if (item.key !== skip && resume > 0 && resume < duration - 2 && time < 8 && Math.abs(time - resume) > 1) {
        var seekable = video.seekable;
        var target = false;
        for (var i = 0; seekable && i < seekable.length; i++)
          if (resume >= seekable.start(i) && resume <= seekable.end(i)) target = true;
        if (!target) return null;
        try { video.currentTime = resume; } catch (ignored) { return null; }
        session.restored = true;
        return null;
      }
      session.restored = true;
    }
    if (time < 1 && !video.ended) return null;
    var done = !!video.ended || duration - time <= 2;
    saved[item.key] = done ? 0 : time;
    if (skip === item.key) skip = '';
    return {key:item.key,title:item.title,position:time,duration:duration,completed:done};
  }
  window.__gofilmHistory = {
    configure:function (values,restart) { saved = values || {}; skip = restart || ''; },
    sample:sample
  };
})();
