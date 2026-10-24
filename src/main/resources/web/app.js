// MWC minimal frontend.
// All API calls use relative paths (no leading slash) so the page works
// under any base-path (e.g. / or /admin/mc/).
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var loginView = $('login-view');
  var appView = $('app-view');
  var statusEl = $('status');
  var playersBody = $('players-body');
  var consoleEl = $('console');
  var connEl = $('conn');

  var CONSOLE_MAX_LINES = 1000;
  var es = null;
  var inApp = false;

  // ---------- auth / views ----------

  function setConn(text, bad) {
    connEl.textContent = text;
    connEl.classList.toggle('bad', !!bad);
  }

  function showLogin() {
    inApp = false;
    loginView.classList.remove('hidden');
    appView.classList.add('hidden');
    closeSse();
  }

  function showApp() {
    inApp = true;
    loginView.classList.add('hidden');
    appView.classList.remove('hidden');
    connectSse();
    pollStatus();
    pollPlayers();
  }

  // fetch wrapper. A 401 on any non-login API means the session expired.
  function api(path, opts) {
    return fetch(path, opts).then(function (res) {
      if (res.status === 401 && path !== 'api/login') showLogin();
      return res;
    });
  }

  function postJson(path, body) {
    return api(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-MWC-Request': 'mwc' },
      body: JSON.stringify(body)
    });
  }

  $('login-form').addEventListener('submit', function (ev) {
    ev.preventDefault();
    var err = $('login-error');
    err.textContent = '';
    postJson('api/login', { token: $('token').value }).then(function (res) {
      if (res.ok) {
        $('token').value = '';
        showApp();
      } else {
        err.textContent = res.status === 429 ? '試行回数が多いです。しばらく待ってから再試行してください。' : 'トークンが違います';
      }
    }).catch(function () { err.textContent = '接続できません'; });
  });

  // ---------- status (poll every 2s) ----------

  function fmtUptime(s) {
    var d = Math.floor(s / 86400);
    var h = Math.floor((s % 86400) / 3600);
    var m = Math.floor((s % 3600) / 60);
    return (d > 0 ? d + 'd ' : '') + h + 'h ' + m + 'm ' + (s % 60) + 's';
  }

  function pollStatus() {
    if (!inApp) return;
    api('api/status').then(function (res) {
      if (!res.ok) return;
      return res.json().then(function (s) {
        var tps = Array.prototype.map.call(s.tps || [], function (v) { return Number(v).toFixed(1); }).join(' / ');
        statusEl.textContent =
          'TPS: ' + (tps || '?') +
          '  (avg tick ' + s.avg_tick_time_ms + ' ms)    ' +
          'Memory: ' + s.memory_used_mb + ' / ' + s.memory_max_mb + ' MB    ' +
          'Uptime: ' + fmtUptime(s.uptime_seconds) + '    ' +
          'Version: ' + s.version + '    ' +
          'Players: ' + s.players_online + ' / ' + s.players_max;
      });
    }).catch(function () { statusEl.textContent = '状態の取得に失敗しました'; });
  }

  // ---------- players (poll every 5s) ----------

  function pollPlayers() {
    if (!inApp) return;
    api('api/players').then(function (res) {
      if (!res.ok) return;
      return res.json().then(function (list) {
        var frag = document.createDocumentFragment();
        if (list.length === 0) {
          var tr0 = document.createElement('tr');
          var td0 = document.createElement('td');
          td0.colSpan = 4;
          td0.className = 'empty';
          td0.textContent = '(オンラインプレイヤーはいません)';
          tr0.appendChild(td0);
          frag.appendChild(tr0);
        } else {
          list.forEach(function (p) {
            var tr = document.createElement('tr');
            [[p.name, ''], [p.uuid, 'uuid'], [p.ping + ' ms', ''], [p.world, '']].forEach(function (c) {
              var td = document.createElement('td');
              if (c[1]) td.className = c[1];
              td.textContent = c[0];
              tr.appendChild(td);
            });
            frag.appendChild(tr);
          });
        }
        playersBody.replaceChildren(frag);
      });
    }).catch(function () { /* ignore transient errors */ });
  }

  // ---------- console (SSE) ----------

  function appendLine(text, cls) {
    var nearBottom = consoleEl.scrollHeight - consoleEl.scrollTop - consoleEl.clientHeight < 48;
    var div = document.createElement('div');
    div.className = 'line' + (cls ? ' ' + cls : '');
    div.textContent = text;
    consoleEl.appendChild(div);
    while (consoleEl.childNodes.length > CONSOLE_MAX_LINES) {
      consoleEl.removeChild(consoleEl.firstChild);
    }
    if (nearBottom) consoleEl.scrollTop = consoleEl.scrollHeight;
  }

  function connectSse() {
    closeSse();
    es = new EventSource('api/console');
    es.onopen = function () { setConn('connected', false); };
    es.onmessage = function (ev) { appendLine(ev.data); };
    es.onerror = function () { setConn('reconnecting…', true); };
  }

  function closeSse() {
    if (es) { es.close(); es = null; }
    setConn('', false);
  }

  $('cmd-form').addEventListener('submit', function (ev) {
    ev.preventDefault();
    var cmd = $('cmd').value.trim();
    if (!cmd) return;
    $('cmd').value = '';
    appendLine('> ' + cmd, 'cmd');
    postJson('api/command', { command: cmd }).then(function (res) {
      if (res.ok) return;
      return res.json().catch(function () { return {}; }).then(function (j) {
        appendLine('(command failed: ' + (j.error || res.status) + ')', 'err');
      });
    }).catch(function () { appendLine('(command send failed)', 'err'); });
  });

  // ---------- init: straight to app when a valid session cookie exists ----------

  api('api/status').then(function (res) {
    if (res.ok) showApp(); else showLogin();
  }).catch(function () { showLogin(); });

  setInterval(pollStatus, 2000);
  setInterval(pollPlayers, 5000);
})();
