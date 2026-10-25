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

  // 状態表示の色(Bootstrap の文字色クラス)。空の文字列では色を付けない
  function setMsg(el, text, bad) {
    el.textContent = text;
    el.classList.toggle('text-danger', !!text && !!bad);
    el.classList.toggle('text-success', !!text && !bad);
  }

  // ---------- tabs (#main / #players / #files) ----------

  var TABS = ['main', 'players', 'files'];

  function showTab() {
    var name = location.hash.slice(1);
    if (TABS.indexOf(name) < 0) name = 'main';
    TABS.forEach(function (t) {
      $('tab-' + t).classList.toggle('hidden', t !== name);
      var link = document.querySelector('[data-tab="' + t + '"]');
      link.classList.toggle('active', t === name);
      if (t === name) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current');
    });
    // 隠れている間に届いた行は位置が合わないので、メインに戻ったら末尾を表示する
    if (name === 'main') consoleEl.scrollTop = consoleEl.scrollHeight;
  }

  window.addEventListener('hashchange', showTab);
  showTab();

  // ---------- auth / views ----------

  function setConn(text, bad) {
    connEl.textContent = text;
    connEl.classList.toggle('conn-ok', !!text && !bad);
    connEl.classList.toggle('conn-bad', !!text && !!bad);
  }

  function showLogin() {
    inApp = false;
    loginView.classList.remove('hidden');
    appView.classList.add('hidden');
    closeSse();
  }

  function showApp() {
    inApp = true;
    // 再起動の後にログインし直したとき、前の状態表示を残さない
    $('restart-btn').disabled = false;
    setRestartStatus('', false);
    setFileMsg('', false);
    loginView.classList.add('hidden');
    appView.classList.remove('hidden');
    connectSse();
    pollStatus();
    pollPlayers();
    showFiles();
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
        var frag = document.createDocumentFragment();
        [['TPS', (tps || '?') + ' (avg tick ' + Number(s.avg_tick_time_ms).toFixed(2) + ' ms)'],
         ['Memory', s.memory_used_mb + ' / ' + s.memory_max_mb + ' MB'],
         ['Uptime', fmtUptime(s.uptime_seconds)],
         ['Version', s.version],
         ['Players', s.players_online + ' / ' + s.players_max]].forEach(function (c) {
          var item = document.createElement('span');
          item.className = 'item';
          var label = document.createElement('span');
          label.className = 'label';
          label.textContent = c[0];
          item.appendChild(label);
          item.appendChild(document.createTextNode(c[1]));
          frag.appendChild(item);
        });
        statusEl.replaceChildren(frag);
      });
    }).catch(function () { statusEl.textContent = '状態の取得に失敗しました'; });
  }

  // ---------- restart ----------

  function setRestartStatus(text, bad) {
    setMsg($('restart-status'), text, bad);
  }

  $('restart-btn').addEventListener('click', function () {
    if (!window.confirm('サーバーを再起動しますか？\n全プレイヤーが切断され、復帰まで十数秒かかります。')) return;
    $('restart-btn').disabled = true;
    setRestartStatus('再起動しています…', false);
    postJson('api/restart', { confirm: true }).then(function (res) {
      if (!res.ok) {
        setRestartStatus('再起動の送信に失敗 (' + res.status + ')', true);
        $('restart-btn').disabled = false;
        return;
      }
      setRestartStatus('再起動中です。サーバー復帰までお待ちください。', false);
    }).catch(function () {
      setRestartStatus('サーバーと通信できません', true);
    });
  });

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
          td0.className = 'text-body-secondary fst-italic';
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
        $('players-count').textContent = list.length;
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
    // 接続のたびにサーバーがバッファ全体を送り直すので、表示を空にしてから受け取る(再接続で行が重複しない)
    es.onopen = function () { consoleEl.replaceChildren(); setConn('connected', false); };
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

  // ---------- files (list + text editor) ----------

  var fileState = { path: '', editing: null, etag: null, busy: false };

  function joinPath(base, name) {
    if (!base) return name;
    return base + '/' + name;
  }

  function fmtSize(n) {
    if (n == null) return '';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / (1024 * 1024)).toFixed(1) + ' MB';
  }

  function fmtTime(ms) {
    var d = new Date(ms);
    function p(x) { return x < 10 ? '0' + x : '' + x; }
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
      ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }

  function fileEl(id) { return $(id); }

  // エディタ内の状態表示(読み込み・保存)
  function setFileStatus(text, bad) {
    setMsg(fileEl('file-editor-status'), text, bad);
  }

  // 一覧の上の状態表示(アップロード・削除)。エディタを閉じていても見える
  function setFileMsg(text, bad) {
    setMsg(fileEl('file-msg'), text, bad);
  }

  // 既定の許可ルート(server.properties・bukkit.yml・spigot.yml・config/・plugins/)の変更は、どれも再起動で反映される
  var RESTART_NOTE = '(サーバーの再起動後に反映されます)';

  function renderPath() {
    var el = fileEl('file-path');
    el.textContent = '/' + fileState.path;
    fileEl('file-up').classList.toggle('hidden', !fileState.path);
  }

  function listFiles() {
    api('api/files?path=' + encodeURIComponent(fileState.path)).then(function (res) {
      if (!res.ok) {
        var tb = fileEl('files-body');
        tb.innerHTML = '';
        var tr = document.createElement('tr');
        var td = document.createElement('td');
        td.colSpan = 5;
        td.className = 'text-danger';
        td.textContent = '一覧の取得に失敗しました (' + res.status + ')';
        tr.appendChild(td);
        tb.appendChild(tr);
        return;
      }
      return res.json().then(function (list) {
        var frag = document.createDocumentFragment();
        if (list.length === 0) {
          var tr0 = document.createElement('tr');
          var td0 = document.createElement('td');
          td0.colSpan = 5;
          td0.className = 'text-body-secondary fst-italic';
          td0.textContent = '(空)';
          tr0.appendChild(td0);
          frag.appendChild(tr0);
        }
        list.forEach(function (f) {
          var tr = document.createElement('tr');
          tr.className = 'file-row';
          var name = document.createElement('td');
          name.textContent = (f.type === 'dir' ? '📁 ' : '📄 ') + f.name;
          var type = document.createElement('td');
          type.textContent = f.type === 'dir' ? 'dir' : 'file';
          var size = document.createElement('td');
          size.textContent = f.type === 'dir' ? '' : fmtSize(f.size);
          var mtime = document.createElement('td');
          mtime.textContent = f.mtime ? fmtTime(f.mtime) : '';
          var act = document.createElement('td');
          var del = document.createElement('button');
          del.type = 'button';
          del.className = 'btn btn-sm btn-outline-danger py-0';
          del.textContent = '削除';
          del.title = '削除';
          del.addEventListener('click', function (ev) {
            ev.stopPropagation();
            deleteEntry(joinPath(fileState.path, f.name), f.type);
          });
          // 許可ルートそのもの(一番上の一覧)は削除できないので、ボタンを出さない
          if (fileState.path) act.appendChild(del);
          tr.appendChild(name); tr.appendChild(type); tr.appendChild(size); tr.appendChild(mtime); tr.appendChild(act);
          tr.addEventListener('click', function () {
            var rel = joinPath(fileState.path, f.name);
            if (f.type === 'dir') {
              fileState.path = rel;
              renderPath();
              closeEditor();
              listFiles();
            } else {
              openEditor(rel);
            }
          });
          frag.appendChild(tr);
        });
        fileEl('files-body').replaceChildren(frag);
      });
    }).catch(function () { /* ignore transient errors */ });
  }

  function openEditor(rel) {
    fileState.editing = rel;
    fileEl('file-editor').classList.remove('hidden');
    fileEl('file-editor-name').textContent = rel;
    setFileStatus('読み込み中…', false);
    fileEl('file-content').value = '';
    api('api/file?path=' + encodeURIComponent(rel)).then(function (res) {
      if (!res.ok) {
        var msg = { 403: '保護対象のため閲覧できません', 404: 'ファイルが見つかりません',
                     413: 'サイズが大きすぎるため編集できません', 415: 'テキストファイルのみ編集できます',
                     400: '読み込めません' }[res.status] || ('エラー ' + res.status);
        setFileStatus(msg, true);
        return;
      }
      fileState.etag = res.headers.get('ETag') || '';
      return res.text().then(function (text) {
        fileEl('file-content').value = text;
        setFileStatus(fileState.etag ? '' : '(ETag なし。保存できません)', !fileState.etag);
      });
    }).catch(function () { setFileStatus('読み込めません', true); });
  }

  function closeEditor() {
    fileState.editing = null;
    fileState.etag = null;
    fileEl('file-editor').classList.add('hidden');
    setFileStatus('', false);
  }

  function saveEditor() {
    if (fileState.busy || !fileState.editing) return;
    if (!fileState.etag) { setFileStatus('ETag なしのため保存できません。一覧から開き直してください。', true); return; }
    fileState.busy = true;
    setFileStatus('保存中…', false);
    api('api/file?path=' + encodeURIComponent(fileState.editing), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'X-MWC-Request': 'mwc' },
      body: JSON.stringify({ content: fileEl('file-content').value, etag: fileState.etag })
    }).then(function (res) {
      if (res.ok) {
        setFileStatus('保存しました' + ' ' + RESTART_NOTE, false);
        listFiles();
        // 保存後の ETag を取り直す
        api('api/file?path=' + encodeURIComponent(fileState.editing)).then(function (r2) {
          if (r2.ok) fileState.etag = r2.headers.get('ETag') || '';
        }).catch(function () { /* ignore */ });
      } else if (res.status === 409) {
        setFileStatus('競合: ファイルが変更されています。再読み込みしてください。', true);
      } else if (res.status === 413) {
        setFileStatus('サイズ上限を超えています', true);
      } else {
        return res.json().catch(function () { return {}; }).then(function (j) {
          setFileStatus('保存失敗: ' + (j.error || res.status), true);
        });
      }
    }).catch(function () { setFileStatus('保存失敗(通信エラー)', true) })
      .then(function () { fileState.busy = false; });
  }

  fileEl('file-refresh').addEventListener('click', function () { closeEditor(); listFiles(); });
  fileEl('file-up').addEventListener('click', function () {
    var parts = fileState.path.split('/');
    parts.pop();
    fileState.path = parts.join('/');
    renderPath();
    closeEditor();
    listFiles();
  });
  fileEl('file-save').addEventListener('click', saveEditor);
  fileEl('file-close').addEventListener('click', closeEditor);

  // ---------- upload + delete ----------

  var uploadInput = fileEl('file-upload-input');

  fileEl('file-upload-btn').addEventListener('click', function () {
    if (fileState.busy) return;
    uploadInput.value = '';
    uploadInput.click();
  });

  uploadInput.addEventListener('change', function () {
    var file = uploadInput.files && uploadInput.files[0];
    if (!file || fileState.busy) return;
    uploadFile(file);
  });

  function uploadFile(file) {
    var dir = fileState.path;
    var name = file.name;
    var dest = joinPath(dir, name);
    fileState.busy = true;
    setFileMsg('アップロード中…', false);
    function doUpload(overwrite) {
      var url = 'api/files/upload?path=' + encodeURIComponent(dest) + (overwrite ? '&overwrite=true' : '');
      return api(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/octet-stream', 'X-MWC-Request': 'mwc' },
        body: file
      }).then(function (res) {
        if (res.ok) {
          setFileMsg('アップロードしました: ' + dest + ' (' + fmtSize(file.size) + ')' +
            ' ' + RESTART_NOTE, false);
          listFiles();
          return;
        }
        if (res.status === 409 && !overwrite) {
          // 既存ファイルの上書き確認(確認ダイアログ必須)
          if (window.confirm('"' + name + '" は既に存在します。上書きしますか?\n(上書き前に .bak が作成されます)')) {
            return doUpload(true);
          }
          setFileMsg('アップロードをキャンセルしました', false);
          return;
        }
        return res.json().catch(function () { return {}; }).then(function (j) {
          setFileMsg('アップロード失敗: ' + (j.error || res.status), true);
        });
      }).catch(function () { setFileMsg('アップロード失敗(通信エラー)', true); })
        .then(function () { fileState.busy = false; });
    }
    doUpload(false);
  }

  function deleteEntry(rel, type) {
    var what = type === 'dir' ? 'ディレクトリ' : 'ファイル';
    var msg = '"' + rel + '" (' + what + ') を削除します。この操作は取り消せません。続行しますか?';
    if (!window.confirm(msg)) return;
    if (type === 'dir' && !window.confirm('ディレクトリ削除は空のディレクトリのみ対象です。続行しますか?')) return;
    fileState.busy = true;
    api('api/files?path=' + encodeURIComponent(rel) + '&confirm=true', {
      method: 'DELETE',
      headers: { 'X-MWC-Request': 'mwc' }
    }).then(function (res) {
      if (res.ok) {
        setFileMsg('削除しました: ' + rel + ' ' + RESTART_NOTE, false);
        if (fileState.editing === rel) closeEditor();
        listFiles();
        return;
      }
      return res.json().catch(function () { return {}; }).then(function (j) {
        setFileMsg('削除失敗: ' + (j.error || res.status), true);
      });
    }).catch(function () { setFileMsg('削除失敗(通信エラー)', true); })
      .then(function () { fileState.busy = false; });
  }

  function showFiles() {
    fileState.path = '';
    renderPath();
    closeEditor();
    listFiles();
  }

  // ---------- init: straight to app when a valid session cookie exists ----------

  api('api/status').then(function (res) {
    if (res.ok) { showApp(); showFiles(); } else showLogin();
  }).catch(function () { showLogin(); });

  setInterval(pollStatus, 2000);
  setInterval(pollPlayers, 5000);
})();
