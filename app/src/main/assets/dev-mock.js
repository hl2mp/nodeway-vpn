/*
 * Мок Android-моста для отладки вёрстки в обычном браузере.
 *
 * Включается только явно: index.html?demo=1
 * Подключается к index.html через <script src="dev-mock.js"></script>.
 *
 * Зачем: в WebView состояние приходит из window.NodewayVpn, поэтому файл,
 * открытый в браузере, остаётся пустым. Этот скрипт подставляет тот же
 * интерфейс на демо-данных: можно смотреть вёрстку и все состояния
 * (подключение, ошибка, загрузка подписки, пустые списки) без сборки APK.
 *
 * В приложении файл безвреден — там условие demo не выполняется,
 * нативный мост остаётся главным.
 */
(function () {
  'use strict';

  var params = new URLSearchParams(window.location.search);
  if (!params.has('demo')) return;
  // В приложении нативный мост важнее мока: не подменяем живое состояние.
  if (window.NodewayVpn) return;

  // ------------------------------------------------------------------ данные

  /** Профиль в том же виде, что отдаёт ProfileStore.toUiModel(). */
  function makeProfile(id, name, detail, extra) {
    var base = {
      id: id,
      name: name,
      detail: detail,
      source: 'subscription',
      fromSubscription: true,
      subscriptionId: 'sub-1',
      subscriptionName: 'Nodeway - VPN',
      address: 'cf.example.com',
      port: 443,
      network: 'tcp',
      security: 'tls',
      sni: 'cf.example.com',
      link: 'vless://demo@cf.example.com:443?type=tcp&security=tls#' + name
    };
    return Object.assign(base, extra || {});
  }

  var scenarios = {
    /** Обычное состояние: подписка с профилями и локальные профили. */
    default: function () {
      return {
        connectedAt: 0,
        selectedProfileId: 'p-2',
        activeProfileId: '',
        subscriptions: [sub(1, 'Nodeway - VPN', 'https://panel.example.com/sub/token', 60, '2 мин назад')],
        profiles: [
          makeProfile('p-1', 'CF Основной (TLS)', 'cf.example.com:443 · TLS/TCP'),
          makeProfile('p-2', 'CF Резервный (Reality)', 'cf2.example.com:443 · REALITY/XHTTP', {
            network: 'xhttp',
            security: 'reality',
            sni: 'www.microsoft.com'
          }),
          makeProfile('p-3', 'Мобильный (WS)', 'cf3.example.com:8443 · TLS/WS', {
            network: 'ws',
            address: 'cf3.example.com',
            port: 8443
          })
        ]
      };
    },

    /** Подписка грузится: у кнопки «Обновить» должен быть спиннер. */
    loading: function () {
      var model = scenarios.default();
      model.subscriptions[0].loading = true;
      return model;
    },

    /** Подключено: активный профиль и ненулевое время подключения. */
    connected: function () {
      var model = scenarios.default();
      model.activeProfileId = 'p-2';
      model.connectedAt = Date.now() - 3 * 60 * 1000 - 21 * 1000;
      return model;
    },

    /** Несколько подписок: проверка вёрстки списка и свёрнутых групп. */
    many: function () {
      return {
        connectedAt: 0,
        selectedProfileId: 'p-1',
        activeProfileId: '',
        subscriptions: [
          sub(1, 'Nodeway - VPN', 'https://panel.example.com/sub/token', 60, '2 мин назад'),
          sub(2, 'Быстрый доступ', 'https://fast.example.com/sub/abc', 30, 'только что'),
          sub(3, 'Рабочий — резерв', 'https://backup.example.org/list', 720, 'ещё не обновлялась')
        ],
        profiles: [
          makeProfile('p-1', 'CF Основной (TLS)', 'cf.example.com:443 · TLS/TCP'),
          makeProfile('p-2', 'CF Резервный (Reality)', 'cf2.example.com:443 · REALITY/XHTTP', {
            network: 'xhttp', security: 'reality', sni: 'www.microsoft.com'
          }),
          makeProfile('p-4', 'EU Frankfurt', 'eu.example.net:443 · TLS/TCP', {
            subscriptionId: 'sub-2', subscriptionName: 'Быстрый доступ', address: 'eu.example.net'
          }),
          makeProfile('p-5', 'US New York', 'us.example.net:443 · TLS/WS', {
            subscriptionId: 'sub-2', subscriptionName: 'Быстрый доступ', address: 'us.example.net'
          }),
          makeProfile('p-6', 'Локальный профиль', 'home.example:51820 · TLS/TCP', {
            fromSubscription: false, subscriptionId: null, subscriptionName: '',
            address: 'home.example', port: 51820
          })
        ]
      };
    },

    /** Пусто: ни профилей, ни подписок — вид первого запуска. */
    empty: function () {
      return { connectedAt: 0, selectedProfileId: '', activeProfileId: '', subscriptions: [], profiles: [] };
    }
  };

  /** Подписка в том же виде, что в UI-модели ProfileStore. */
  function sub(number, name, url, refreshMinutes, updatedLabel) {
    return {
      id: 'sub-' + number,
      name: name,
      url: url,
      host: url.replace(/^https?:\/\//, '').split('/')[0],
      refreshLabel: 'автообновление раз в ' + (refreshMinutes / 60) + ' ч',
      updatedLabel: updatedLabel,
      nextRefreshInMinutes: refreshMinutes,
      loading: false,
      count: 2
    };
  }

  /** Приложения для листа выбора: подборка для демо, не настоящий список пакетов. */
  var mockApps = [
    { pkg: 'com.example.browser', label: 'Браузер', system: false, color: '#007AFF' },
    { pkg: 'com.example.chat', label: 'Мессенджер', system: false, color: '#34C759' },
    { pkg: 'com.example.video', label: 'Видео', system: false, color: '#FF3B30' },
    { pkg: 'com.example.bank', label: 'Банк', system: false, color: '#FF9500' },
    { pkg: 'com.example.mail', label: 'Почта', system: false, color: '#5856D6' },
    { pkg: 'com.example.cloud', label: 'Облако', system: false, color: '#8A8A8E' },
    { pkg: 'com.android.settings', label: 'Настройки', system: true, color: '#636366' },
    { pkg: 'com.android.chrome', label: 'Chrome', system: true, color: '#0F9D58' },
    { pkg: 'com.android.vending', label: 'Play Маркет', system: true, color: '#00D0FF' }
  ];

  var state = {
    model: scenarios.default(),
    vpnState: 'disconnected',
    connectedAt: 0,
    splitMode: 'all',
    splitPackages: new Set()
  };

  // ------------------------------------------------------------------ мост

  function emitProfiles() {
    if (window.onProfilesChanged) window.onProfilesChanged(JSON.stringify(state.model));
  }

  function emitVpn(next, message) {
    state.vpnState = next;
    if (window.onVpnState) {
      window.onVpnState(JSON.stringify({ state: next, message: message || '' }));
    }
  }

  window.NodewayVpn = {
    getInitialState: function () {
      return JSON.stringify({
        state: state.model,
        coreVersion: '26.9.30 (mock)',
        appVersion: '1.0'
      });
    },

    uiState: function () { return this.getInitialState(); },

    /* ---------- раздельное туннелирование ---------- */

    getSplitTunnelSettings: function () {
      return JSON.stringify({
        mode: state.splitMode,
        packages: Array.from(state.splitPackages),
        selfPackage: 'com.nodewayvpn.pro'
      });
    },

    setSplitTunnelMode: function (mode) {
      if (mode === 'allow' && state.splitPackages.size === 0) {
        return JSON.stringify({ ok: false, error: 'Выберите хотя бы одно приложение' });
      }
      state.splitMode = mode;
      return JSON.stringify({ ok: true });
    },

    setPackageSelected: function (pkg, selected) {
      if (selected) state.splitPackages.add(pkg);
      else state.splitPackages.delete(pkg);
      return JSON.stringify({ ok: true });
    },

    setSelectedPackages: function (packagesJson) {
      var list;
      try {
        list = JSON.parse(packagesJson) || [];
      } catch (e) {
        return JSON.stringify({ ok: false, error: 'Не удалось разобрать список' });
      }
      state.splitPackages = new Set(list);
      return JSON.stringify({ ok: true });
    },

    listInstalledApps: function (includeSystem, query) {
      var needle = (query || '').trim().toLowerCase();
      return JSON.stringify(
        mockApps
          .filter(function (app) { return includeSystem || !app.system; })
          .filter(function (app) {
            if (!needle) return true;
            return app.label.toLowerCase().indexOf(needle) >= 0 || app.pkg.indexOf(needle) >= 0;
          })
          .sort(function (a, b) {
            var sa = state.splitPackages.has(a.pkg) ? 0 : 1;
            var sb = state.splitPackages.has(b.pkg) ? 0 : 1;
            return sa - sb || a.label.localeCompare(b.label);
          })
      );
    },

    getAppIcon: function (pkg) {
      var app = mockApps.filter(function (item) { return item.pkg === pkg; })[0];
      if (!app) return '';
      var mark = '<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96">' +
        '<rect width="96" height="96" rx="20" fill="' + app.color + '"/>' +
        '<text x="48" y="65" font-family="sans-serif" font-size="46" fill="#fff" ' +
        'text-anchor="middle">' + app.label.charAt(0).toUpperCase() + '</text></svg>';
      return 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(mark)));
    },

    requestConnect: function (profileId) {
      if (!profileId) return;
      emitVpn('connecting', 'Подключаемся…');
      setTimeout(function () {
        state.model.activeProfileId = profileId;
        state.model.selectedProfileId = profileId;
        state.connectedAt = Date.now();
        state.model.connectedAt = state.connectedAt;
        emitVpn('connected', 'Подключено');
        emitProfiles();
      }, 900);
    },

    requestDisconnect: function () {
      emitVpn('disconnecting', 'Отключаемся…');
      setTimeout(function () {
        state.model.activeProfileId = '';
        state.model.connectedAt = 0;
        state.connectedAt = 0;
        emitVpn('disconnected', 'Отключено');
        emitProfiles();
      }, 700);
    },

    selectProfile: function (id) {
      state.model.selectedProfileId = id;
      emitProfiles();
    },

    refreshSubscription: function (id) {
      var target = state.model.subscriptions.filter(function (s) { return s.id === id; })[0];
      if (!target) return;
      target.loading = true;
      emitProfiles();
      setTimeout(function () {
        target.loading = false;
        target.updatedLabel = 'обновлено только что';
        emitProfiles();
        if (window.onImportResult) {
          window.onImportResult(JSON.stringify({
            ok: true, message: 'Подписка «' + target.name + '» обновлена: профилей 3'
          }));
        }
      }, 2000);
    },

    removeSubscription: function (id) {
      state.model.subscriptions = state.model.subscriptions.filter(function (s) { return s.id !== id; });
      state.model.profiles = state.model.profiles.filter(function (p) { return p.subscriptionId !== id; });
      emitProfiles();
    },

    removeProfile: function (id) {
      state.model.profiles = state.model.profiles.filter(function (p) { return p.id !== id; });
      emitProfiles();
    },

    importFromClipboard: function () {
      var hasSubscription = state.model.subscriptions.length > 0;
      if (hasSubscription) {
        return JSON.stringify({
          ok: true, added: 3, total: 3, unsupported: 1,
          message: 'В буфере обмена уже знакомые ссылки — ничего нового'
        });
      }
      state.model = scenarios.many();
      state.model.subscriptions = state.model.subscriptions.slice(0, 1);
      state.model.subscriptions[0].updatedLabel = 'обновлено только что';
      emitProfiles();
      return JSON.stringify({
        ok: true, added: 5, total: 5, unsupported: 0,
        message: 'Загружаем подписку: panel.example.com'
      });
    },

    coreVersion: function () { return '26.9.30 (mock)'; },
    appVersion: function () { return '1.0'; }
  };

  // ------------------------------------------------------------------ панель

  // Панель сворачивается в одну кнопку, чтобы не мешала смотреть вёрстку.
  var PANEL_KEY = 'nodeway.demoPanel';
  var panelOpen = readPanelOpen();

  function readPanelOpen() {
    try {
      return localStorage.getItem(PANEL_KEY) !== 'closed';
    } catch (e) {
      return true;
    }
  }

  var toolbar = document.createElement('div');
  toolbar.style.cssText = [
    'position:fixed', 'left:8px', 'bottom:28px', 'z-index:9999',
    'display:flex', 'flex-direction:column', 'gap:6px', 'max-width:320px',
    'padding:8px', 'border-radius:12px',
    'background:rgba(255,255,255,.94)', 'box-shadow:0 6px 24px rgba(0,0,0,.18)',
    'font:12px/1.4 -apple-system,Roboto,Arial,sans-serif', 'color:#0B0F14'
  ].join(';');
  document.body.appendChild(toolbar);

  function styleButton(button) {
    button.style.cssText = [
      'border:0', 'border-radius:8px', 'padding:6px 10px',
      'background:#0B0F14', 'color:#fff', 'cursor:pointer'
    ].join(';');
    return button;
  }

  var buttons = document.createElement('div');
  buttons.style.cssText = 'display:flex;flex-wrap:wrap;gap:6px';

  // Кнопка-переключатель лежит вне buttons, иначе сворачивание скрыло бы и её.
  var toggle = styleButton(document.createElement('button'));
  toggle.textContent = 'Demo ▾';
  // По ширине надписи, а не во всю панель — так её проще нащупать.
  toggle.style.alignSelf = 'flex-start';
  toggle.onclick = function () {
    panelOpen = !panelOpen;
    applyPanelState();
    try {
      localStorage.setItem(PANEL_KEY, panelOpen ? 'open' : 'closed');
    } catch (e) { /* без localStorage просто не запоминаем */ }
  };
  toolbar.appendChild(toggle);
  toolbar.appendChild(buttons);

  function addButton(label, onClick) {
    var button = styleButton(document.createElement('button'));
    button.textContent = label;
    button.onclick = onClick;
    buttons.appendChild(button);
    return button;
  }

  function applyPanelState() {
    buttons.style.display = panelOpen ? 'flex' : 'none';
    toggle.textContent = panelOpen ? 'Demo ▾' : 'Demo ▸';
    toggle.style.background = panelOpen ? '#0B0F14' : '#007AFF';
  }

  // У сценария может быть своё состояние туннеля — иначе кнопка питания
  // не переключится, ведь index.html берёт его из onVpnState.
  var scenarioVpnState = { connected: 'connected', loading: 'disconnected' };

  [
    ['модель', 'default'],
    ['много подписок', 'many'],
    ['загрузка', 'loading'],
    ['подключено', 'connected'],
    ['пусто', 'empty']
  ].forEach(function (pair) {
    addButton(pair[0], function () {
      var previous = state.vpnState;
      state.model = scenarios[pair[1]]();
      var vpnState = scenarioVpnState[pair[1]] || previous;

      if (vpnState === 'connected') {
        var first = state.model.profiles[0];
        state.model.activeProfileId = first ? first.id : '';
        state.connectedAt = Date.now();
        state.model.connectedAt = state.connectedAt;
      } else {
        state.connectedAt = 0;
      }

      emitProfiles();
      emitVpn(vpnState, '');
    });
  });

  addButton('подключить', function () { window.NodewayVpn.requestConnect(state.model.selectedProfileId); });
  addButton('отключить', function () { window.NodewayVpn.requestDisconnect(); });
  addButton('ошибка', function () {
    emitVpn('error', 'Не удалось подключиться: сервер не отвечает');
  });

  applyPanelState();
})();