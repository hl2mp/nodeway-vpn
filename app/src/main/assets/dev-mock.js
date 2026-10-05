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
 * Дополнительно: index.html?demo=1&delay=800 добавляет задержку ответа,
 * чтобы увидеть индикатор загрузки в списке приложений.
 *
 * Мок умеет и журнал: на старте отдаёт готовый хвост с обоих ядер, дописывает
 * строки при подключении, а кнопка «логи» в панели переключает выключатель из
 * настроек вместе с карточкой журнала.
 *
 * Проверка откликов тоже разыгрывается целиком: кнопки в плашках групп и
 * кнопка «проверка» в панели пускают очередь по всем профилям, плашки
 * наполняются по мере замеров, а по завершении состояние возвращается к тому,
 * каким было до проверки. События повторяют сервис, а не украшают его, —
 * иначе в браузере не повторились бы его собственные огрехи.
 *
 * Отклик под таймером тоже оживает: замер появляется вместе с подключением,
 * слегка дрейфует от значения, выведенного из профиля, и пропадает на гашении.
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

  /*
   * Искусственная задержка ответа в миллисекундах: ?demo=1&delay=800
   *
   * Нужна, чтобы увидеть индикатор загрузки: без неё мок отвечает мгновенно
   * и спиннер не успевает появиться. Ответ в этом случае уходит через Promise.
   */
  var delayMs = Math.max(0, parseInt(params.get('delay'), 10) || 0);

  /** Возвращает значение сразу или через Promise, если задана задержка. */
  function respond(value) {
    if (!delayMs) return value;
    return new Promise(function (resolve) {
      setTimeout(function () { resolve(value); }, delayMs);
    });
  }

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
        subscriptions: [sub(1, 'Nodeway - VPN', 'https://panel.example.com/sub/token', 60, 2)],
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
          sub(1, 'Nodeway - VPN', 'https://panel.example.com/sub/token', 60, 2),
          sub(2, 'Быстрый доступ', 'https://fast.example.com/sub/abc', 30, 0),
          sub(3, 'Рабочий — резерв', 'https://backup.example.org/list', 720, null),
          // Ноль минут — автообновление выключено: строку с отсчётом прячет.
          sub(4, 'Ручная подписка', 'https://manual.example.net/sub', 0, 45)
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

  /**
   * Подписка в том же виде, что в UI-модели ProfileStore.
   *
   * updatedMinutes назад — сколько прошло с последнего обновления; при null
   * подписка ещё ни разу не обновлялась и отсчёт идёт от addedAt.
   */
  function sub(number, name, url, refreshMinutes, updatedMinutes) {
    var now = Date.now();
    return {
      id: 'sub-' + number,
      name: name,
      url: url,
      host: url.replace(/^https?:\/\//, '').split('/')[0],
      refreshMinutes: refreshMinutes,
      updatedAt: updatedMinutes === null ? 0 : now - updatedMinutes * 60000,
      addedAt: now - (3 * 60) * 60000,
      loading: false,
      count: 2
    };
  }

  /**
 * Приложения для листа выбора: подборка для демо, не настоящий список пакетов.
 *
 * Их намеренно много: так в браузере видно, что иконки подгружаются порциями
 * по мере прокрутки, а не все сразу.
 */
    var mockApps = [
      { pkg: 'com.example.browser', label: 'Браузер', system: false, color: '#007AFF' },
      { pkg: 'com.example.chat', label: 'Мессенджер', system: false, color: '#34C759' },
      { pkg: 'com.example.video', label: 'Видео', system: false, color: '#FF3B30' },
      { pkg: 'com.example.bank', label: 'Банк', system: false, color: '#FF9500' },
      { pkg: 'com.example.mail', label: 'Почта', system: false, color: '#5856D6' },
      { pkg: 'com.example.cloud', label: 'Облако', system: false, color: '#8A8A8E' },
      { pkg: 'com.example.music', label: 'Музыка', system: false, color: '#FF2D55' },
      { pkg: 'com.example.maps', label: 'Карты', system: false, color: '#30B0C7' },
      { pkg: 'com.example.social', label: 'Соцсеть', system: false, color: '#0A84FF' },
      { pkg: 'com.example.shop', label: 'Магазин', system: false, color: '#FFCC00' },
      { pkg: 'com.example.news', label: 'Новости', system: false, color: '#AF52DE' },
      { pkg: 'com.example.camera', label: 'Камера', system: false, color: '#636366' },
      { pkg: 'com.example.notes', label: 'Заметки', system: false, color: '#FFD60A' },
      { pkg: 'com.example.weather', label: 'Погода', system: false, color: '#64D2FF' },
      { pkg: 'com.example.fitness', label: 'Фитнес', system: false, color: '#FF375F' },
      { pkg: 'com.example.game', label: 'Игры', system: false, color: '#30D158' },
      { pkg: 'com.example.bank2', label: 'Другая банковская карта', system: false, color: '#BF5AF2' },
      { pkg: 'com.example.drive', label: 'Диск', system: false, color: '#5E5CE6' },
      { pkg: 'com.example.translate', label: 'Переводчик', system: false, color: '#32ADE6' },
      { pkg: 'com.example.wallet', label: 'Кошелёк', system: false, color: '#32D74B' },
      { pkg: 'com.example.calendar', label: 'Календарь', system: false, color: '#FF9F0A' },
      { pkg: 'com.example.dict', label: 'Словарь', system: false, color: '#98989D' },
      { pkg: 'com.example.travel', label: 'Путешествия', system: false, color: '#66D4CF' },
      { pkg: 'com.example.reader', label: 'Читалка', system: false, color: '#AC8E68' },
      { pkg: 'com.example.podcast', label: 'Подкасты', system: false, color: '#D96C75' },
      { pkg: 'com.example.delivery', label: 'Доставка', system: false, color: '#FF6482' },
      { pkg: 'com.example.taxi', label: 'Такси', system: false, color: '#0A84FF' },
      { pkg: 'com.example.work', label: 'Рабочий чат', system: false, color: '#48484A' },
      { pkg: 'com.example.education', label: 'Обучение', system: false, color: '#009639' },
      { pkg: 'com.example.tasks', label: 'Задачи', system: false, color: '#FF453A' },
      { pkg: 'com.example.habits', label: 'Привычки', system: false, color: '#BF5AF2' },
      { pkg: 'com.example.sleep', label: 'Сон', system: false, color: '#5E5CE6' },
      { pkg: 'com.example.stocks', label: 'Акции', system: false, color: '#30B0C7' },
      { pkg: 'com.example.photoeditor', label: 'Фоторедактор', system: false, color: '#FF375F' },
      { pkg: 'com.example.vpnclient', label: 'Другой VPN', system: false, color: '#8E8E93' },
      { pkg: 'com.example.launcher', label: 'Лаунчер', system: false, color: '#3A3A3C' },
      { pkg: 'com.example.keyboard', label: 'Клавиатура', system: false, color: '#636366' },
      { pkg: 'com.example.files', label: 'Файлы', system: false, color: '#0A84FF' },
      { pkg: 'com.example.store', label: 'Магазин приложений', system: false, color: '#FF9F0A' },
      { pkg: 'com.example.podcast2', label: 'Аудиокниги', system: false, color: '#BF5AF2' },
      { pkg: 'com.example.radio', label: 'Радио', system: false, color: '#FF453A' },
      { pkg: 'com.example.smart', label: 'Умный дом', system: false, color: '#64D2FF' },
      { pkg: 'com.example.antivirus', label: 'Антивирус', system: false, color: '#32D74B' },
      { pkg: 'com.example.backup', label: 'Резервные копии', system: false, color: '#5E5CE6' },
      { pkg: 'com.example.notes2', label: 'Заметки 2', system: false, color: '#FFD60A' },
      { pkg: 'com.android.settings', label: 'Настройки', system: true, color: '#636366' },
      { pkg: 'com.android.chrome', label: 'Chrome', system: true, color: '#0F9D58' },
      { pkg: 'com.android.vending', label: 'Play Маркет', system: true, color: '#00D0FF' },
      { pkg: 'com.android.systemui', label: 'Системный интерфейс', system: true, color: '#8A8A8E' },
      { pkg: 'com.android.shell', label: 'Оболочка', system: true, color: '#48484A' },
      { pkg: 'com.android.providers.media', label: 'Медиахранилище', system: true, color: '#30B0C7' },
      { pkg: 'com.android.permissioncontroller', label: 'Контроль разрешений', system: true, color: '#FF9500' },
      { pkg: 'com.android.packageinstaller', label: 'Установка приложений', system: true, color: '#34C759' },
      { pkg: 'com.android.providers.settings', label: 'Провайдер настроек', system: true, color: '#AF52DE' },
      { pkg: 'com.android.keychain', label: 'Хранилище ключей', system: true, color: '#FF2D55' },
      { pkg: 'com.android.location.fused', label: 'Служба местоположения', system: true, color: '#30D158' },
      { pkg: 'com.android.external.storage', label: 'Внешнее хранилище', system: true, color: '#FFD60A' },
      { pkg: 'com.android.frameworks.res', label: 'Ресурсы системы', system: true, color: '#98989D' },
      { pkg: 'com.android.phone', label: 'Телефон', system: true, color: '#34C759' },
      { pkg: 'com.android.deskclock', label: 'Часы', system: true, color: '#FF453A' },
      { pkg: 'com.android.documentsui', label: 'Файлы системы', system: true, color: '#0A84FF' },
      { pkg: 'com.android.camera2', label: 'Камера системы', system: true, color: '#FF9F0A' },
      { pkg: 'com.android.printspooler', label: 'Печать', system: true, color: '#5E5CE6' }
    ];

  /** SVG-заглушка вместо настоящей иконки: цветной квадрат с первой буквой. */
  function mockIcon(app) {
    var mark = '<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96">' +
      '<rect width="96" height="96" rx="20" fill="' + app.color + '"/>' +
      '<text x="48" y="65" font-family="sans-serif" font-size="46" fill="#fff" ' +
      'text-anchor="middle">' + app.label.charAt(0).toUpperCase() + '</text></svg>';
    return 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(mark)));
  }

  var state = {
    model: scenarios.default(),
    vpnState: 'disconnected',
    // Прогресс проверки откликов едет вместе с состоянием — как в сервисе.
    ping: emptyPing(),
    splitMode: 'all',
    splitPackages: new Set(),
    /*
     * В приложении журнал по умолчанию выключен, но для демо его лучше показать
     * сразу: иначе карточка скрыта и половину вёрстки не видно.
     * Переключается кнопкой «логи» в панели внизу.
     */
    journalEnabled: true
  };

  // ------------------------------------------------------------------ журнал

  /**
   * Строки журнала в виде [с секунд назад, источник, уровень, текст].
   *
   * Источники и уровни повторяют настоящие: `app` без префикса источника,
   * ядра со своим, уровень в начале строки.
   */
  var journalScript = [
    [12, 'app', 'info', 'Подключаемся'],
    [12, 'olcrtc', 'info', 'cnc: dialing udp to room.example.com:3478'],
    [11, 'olcrtc', 'info', 'vp8channel: open track=TR_demo1 rtp=120 frames=0'],
    [10, 'xray', 'info', '[Info] [1700000001] app/dns: DNS: created UDP client for tcp+1.1.1.1:53'],
    [10, 'xray', 'warn', '[Warning] core: Xray 26.9.30 started'],
    [9, 'xray', 'info', '[Info] [1700000001] proxy/tun: nodeway0 created'],
    [8, 'xray', 'info', '[Info] [1700000002] transport/internet/tcp: dialing TCP to tcp:127.0.0.1:10808'],
    // Ошибка нужна, чтобы фильтр «проблемы» было что показать.
    [6, 'olcrtc', 'error', 'ERROR: vp8channel: read failed, reconnecting'],
    [5, 'xray', 'info', '[Info] [1700000003] proxy/tun: processing from tcp:10.23.0.2:45710 to tcp:1.1.1.1:443'],
    [4, 'app', 'info', 'Подключено']
  ];

  /** Живой хвост журнала: строки приходят по одной, как от настоящего ядра. */
  var journalTail = [
    ['olcrtc', 'info', 'vp8channel: open track=TR_demo7 rtp=96 frames=0'],
    ['xray', 'info', '[Info] [1700000010] proxy/tun: processing from tcp:10.23.0.2:45711 to tcp:1.1.1.1:443'],
    ['olcrtc', 'info', 'sid=9 tunnel to 1.1.1.1:443']
  ];

  /**
   * Запись журнала. `secondsAgo` отнимается от текущего времени — так собирается
   * готовый хвост, который страница получает задним числом.
   */
  function journalNow(source, level, text, secondsAgo) {
    return { t: Date.now() - (secondsAgo || 0) * 1000, s: source, l: level, m: text };
  }

  function emitJournalReset() {
    if (!state.journalEnabled || !window.onJournalReset) return;
    var entries = journalScript.map(function (row) {
      return journalNow(row[1], row[2], row[3], row[0]);
    });
    window.onJournalReset(JSON.stringify(entries));
  }

  function emitJournal(entries) {
    if (!state.journalEnabled || !window.onJournalEntries) return;
    window.onJournalEntries(JSON.stringify(entries));
  }

  function emitJournalTail() {
    journalTail.forEach(function (row, index) {
      setTimeout(function () {
        emitJournal([journalNow(row[0], row[1], row[2])]);
      }, index * 500);
    });
  }

  // ------------------------------------------------------------------ мост

  function emitProfiles() {
    if (window.onProfilesChanged) window.onProfilesChanged(JSON.stringify(state.model));
  }

  function emitVpn(next, message) {
    state.vpnState = next;
    if (window.onVpnState) {
      // Настоящий мост кладёт в событие ещё прогресс проверки и отклик: без них
      // плашки и строка под таймером в браузере не появлялись бы вовсе.
      window.onVpnState(JSON.stringify({
        state: next,
        message: message || '',
        latency: currentLatency(),
        ping: state.ping
      }));
    }
  }

  /** Активный профиль и время подключения всегда меняются вместе. */
  function setConnection(profileId, connectedAt) {
    state.model.activeProfileId = profileId || '';
    state.model.connectedAt = connectedAt;
    // Отклик живёт столько же, сколько туннель: сервис начинает мерить с его
    // подъёма и обнуляет замер на гашении. Подписка на замеры живёт здесь же —
    // setConnection проходит через любое подключение и отключение, включая
    // возврат после проверки профилей.
    if (state.model.activeProfileId) startLatencyTick();
    else stopLatencyTick();
  }

  /**
   * Туннель погашен, но время подключения ещё прежнее.
   *
   * Отдельная функция ради одной честной детали: сервис не обнуляет connectedAt
   * при остановке, он меняется только при новом подключении. Если обнулять его
   * здесь, счётчик времени прыгал бы в демо там, где в приложении стоит.
   */
  function clearActive() {
    state.model.activeProfileId = '';
    stopLatencyTick();
  }

  // ------------------------------------------------------------ отклик

  /*
   * Отклик под таймером подключения.
   *
   * Сервис меряет сразу при подъёме туннеля и потом раз в минуту, а на гашении
   * обнуляет замер. Мок повторяет и то, и другое: без этого строка под таймером
   * в демо не появлялась бы вовсе и её вёрстку было бы нечем посмотреть.
   */

  /** Пауза между замерами — столько же, сколько PROBE_INTERVAL_MS в сервисе. */
  var LATENCY_STEP_MS = 60 * 1000;

  /** Сколько замеров прошло с начала подключения: от него зависит дрейф. */
  var latencyTick = 0;
  var latencyTimer = null;

  /**
   * Замер для строки под таймером.
   *
   * Отталкивается от id профиля, как и mockLatency в проверке, поэтому одна
   * и та же подписка всегда выглядит одинаково. Дальше — небольшой дрейф
   * вокруг этого значения: столько же величина живёт в приложении, а число,
   * раз за разом одинаковое, выдаёт себя заглушкой.
   */
  function currentLatency() {
    if (state.vpnState !== 'connected' || !state.model.activeProfileId) return 0;
    var base = mockLatency(state.model.activeProfileId);
    // -1 в проверке значит «не отвечает», но у живого туннеля такого заглушка
    // не возвращает: подставляем правдоподобное значение.
    if (base <= 0) base = 180;
    var drift = ((latencyTick * 37) % 41) - 20;
    return Math.max(20, base + drift);
  }

  function stopLatencyTick() {
    if (latencyTimer) clearInterval(latencyTimer);
    latencyTimer = null;
    latencyTick = 0;
  }

  function startLatencyTick() {
    stopLatencyTick();
    latencyTimer = setInterval(function () {
      // Проверка профилей крутит туннель сама: её состояния идут своими
      // событиями, и подмешивать в них наш замер нельзя.
      if (state.vpnState !== 'connected') return;
      latencyTick += 1;
      emitVpn('connected', '');
    }, LATENCY_STEP_MS);
  }

  // ------------------------------------------------------- проверка профилей

  /*
   * Отклики профилей.
   *
   * События повторяют NodewayVpnService один в один, включая два лишних
   * onProfilesChanged — на старте и на финише. Из-за них список пересобирался
   * целиком, а вместе с ним заново проигрывала анимация «галочки» выбранного
   * профиля. Убрать эти вызовы нельзя: без них моргание в браузере не
   * воспроизводится и правку нечем проверить.
   */

  /** Длительность одного замера: в приложении это настоящий запрос по сети. */
  var PING_STEP_MS = 900;

  /** Номер прогона: следующий прогон отменяет предыдущий, как work?.cancel(). */
  var pingRun = 0;

  function emptyPing() {
    return { running: false, index: 0, total: 0, profileId: '', results: {} };
  }

  function profileById(id) {
    return state.model.profiles.filter(function (p) { return p.id === id; })[0];
  }

  /**
   * Отклик профиля — выводится из id, а не из случайного числа: один и тот же
   * профиль должен давать один и тот же результат от прогона к прогону.
   * -1 — «не отвечает», в приложении так помечается профиль, оборванный по
   * таймауту.
   */
  function mockLatency(id) {
    var seed = 0;
    for (var i = 0; i < id.length; i++) seed = (seed * 31 + id.charCodeAt(i)) % 1009;
    return seed % 5 === 0 ? -1 : 40 + seed % 260;
  }

  function parseIds(jsonIds) {
    try {
      var list = JSON.parse(jsonIds);
      if (!Array.isArray(list)) return [];
      return list.filter(function (id) { return typeof id === 'string' && id.length > 0; });
    } catch (e) {
      return [];
    }
  }

  function pingProfiles(jsonIds) {
    var ids = parseIds(jsonIds);
    if (!ids.length || state.ping.running) return;
    runPing(ids);
  }

  function runPing(ids) {
    // Состояние на момент нажатия сервис запоминает и возвращает после прогона.
    var wasConnected = state.vpnState === 'connected' || state.vpnState === 'connecting';
    var restoreId = state.model.activeProfileId;
    var run = ++pingRun;

    state.ping = { running: true, index: 0, total: ids.length, profileId: '', results: {} };

    // Сервис поднимается под проверку с нуля и первым делом публикует
    // «Отключено» отдельным onProfilesChanged: отсюда приходило моргание
    // в момент нажатия.
    clearActive();
    emitVpn('disconnected', '');
    emitProfiles();

    step(0);

    function step(index) {
      if (run !== pingRun) return;
      if (index >= ids.length) {
        finish();
        return;
      }
      state.ping.index = index;
      state.ping.profileId = ids[index];
      emitVpn('pinging', '');
      setTimeout(function () {
        if (run !== pingRun) return;
        state.ping.results[ids[index]] = mockLatency(ids[index]);
        // Событий на профиль два: до замера и с уже измеренным откликом.
        emitVpn('pinging', '');
        setTimeout(function () { step(index + 1); }, PING_STEP_MS / 2);
      }, PING_STEP_MS);
    }

    function finish() {
      if (run !== pingRun) return;
      // total обнуляется: по нему страница понимает, что прогон кончен, и
      // оставляет собранные плашки на месте.
      state.ping = {
        running: false,
        index: 0,
        total: 0,
        profileId: '',
        results: state.ping.results
      };
      if (wasConnected && restoreId) {
        setConnection(restoreId, Date.now());
        emitVpn('connecting', 'Переключаем профиль');
        setTimeout(function () {
          if (run !== pingRun) return;
          emitVpn('connected', 'Подключено');
          emitProfiles();
        }, PING_STEP_MS);
        return;
      }
      clearActive();
      emitVpn('disconnected', '');
      emitProfiles();
    }
  }

  /*
 * Иконки.
 *
 * В приложении картинку отдаёт нативная часть, перехватывая запрос по адресу
 * nodeway.internal/icon/<pkg>: моста в этом нет. В браузере перехвата нет, и
 * такой адрес не резолвится, поэтому страница спрашивает адрес у этого крючка,
 * а мок отдаёт SVG-заглушку.
 */
window.__nodewayIconUrl = function (pkg) {
  var app = mockApps.filter(function (item) { return item.pkg === pkg; })[0];
  return app ? mockIcon(app) : '';
};

window.NodewayVpn = {
    getInitialState: function () {
      // Снимок повторяет настоящий: vpnState и ping лежат внутри state,
      // а не рядом с ним. Копия — ключи не должны попасть в саму модель,
      // откуда её потом уходит onProfilesChanged.
      var snapshot = Object.assign({}, state.model, {
        vpnState: state.vpnState,
        ping: state.ping
      });
      return JSON.stringify({
        state: snapshot,
        coreVersion: '26.9.30 (mock)',
        appVersion: '1.0',
        journalEnabled: state.journalEnabled
      });
    },

    uiState: function () { return this.getInitialState(); },

    /* ---------- журнал ---------- */

    setJournalEnabled: function (enabled) {
      state.journalEnabled = !!enabled;
      if (state.journalEnabled) emitJournalReset();
      if (window.onJournalEnabled) window.onJournalEnabled(state.journalEnabled);
      return JSON.stringify({ enabled: state.journalEnabled });
    },

    /* ---------- раздельное туннелирование ---------- */

    getSplitTunnelSettings: function () {
      return JSON.stringify({
        mode: state.splitMode,
        packages: Array.from(state.splitPackages),
        selfPackage: 'com.nodewayvpn.pro'
      });
    },

    setSplitTunnelMode: function (mode) {
      // Пустой список разрешён: сброс в «Все приложения» делает сама страница.
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
      var apps = mockApps
        .filter(function (app) { return includeSystem || !app.system; })
        .filter(function (app) {
          if (!needle) return true;
          return app.label.toLowerCase().indexOf(needle) >= 0 || app.pkg.indexOf(needle) >= 0;
        })
        .sort(function (a, b) {
          var sa = state.splitPackages.has(a.pkg) ? 0 : 1;
          var sb = state.splitPackages.has(b.pkg) ? 0 : 1;
          return sa - sb || a.label.localeCompare(b.label);
        });
      return respond(JSON.stringify(apps));
    },

    /** Асинхронный вариант: ответ приходит вызовом window.onSplitApps. */
    listInstalledAppsAsync: function (token, includeSystem, query) {
      var payload = this.listInstalledApps(includeSystem, query);
      Promise.resolve(payload).then(function (json) {
        window.onSplitApps && window.onSplitApps(token, json);
      });
    },



    requestConnect: function (profileId) {
      if (!profileId) return;
      emitVpn('connecting', 'Подключаемся…');
      emitJournal([
        journalNow('app', 'info', 'Подключаемся'),
        journalNow('olcrtc', 'info', 'cnc: dialing udp to room.example.com:3478')
      ]);
      setTimeout(function () {
        state.model.selectedProfileId = profileId;
        setConnection(profileId, Date.now());
        emitVpn('connected', 'Подключено');
        emitProfiles();
        emitJournalTail();
      }, 900);
    },

    requestDisconnect: function () {
      emitVpn('disconnecting', 'Отключаемся…');
      emitJournal([journalNow('app', 'info', 'Отключаемся')]);
      setTimeout(function () {
        setConnection('', 0);
        emitVpn('disconnected', 'Отключено');
        emitProfiles();
        emitJournal([journalNow('app', 'info', 'Отключено')]);
      }, 700);
    },

    selectProfile: function (id) {
      state.model.selectedProfileId = id;
      emitProfiles();
    },

    /* ---------- проверка откликов ---------- */

    pingProfiles: pingProfiles,

    refreshSubscription: function (id) {
      var target = state.model.subscriptions.filter(function (s) { return s.id === id; })[0];
      if (!target) return;
      target.loading = true;
      emitProfiles();
      setTimeout(function () {
        target.loading = false;
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
        setConnection(first ? first.id : '', Date.now());
      } else {
        setConnection('', 0);
      }

      emitProfiles();
      emitVpn(vpnState, '');
    });
  });

  // Проверка всех профилей подряд: тот же сценарий, что у кнопок в плашках групп.
  addButton('проверка', function () {
    pingProfiles(JSON.stringify(state.model.profiles.map(function (p) { return p.id; })));
  });

  addButton('подключить', function () { window.NodewayVpn.requestConnect(state.model.selectedProfileId); });
  addButton('отключить', function () { window.NodewayVpn.requestDisconnect(); });
  addButton('ошибка', function () {
    emitVpn('error', 'Не удалось подключиться: сервер не отвечает');
    emitJournal([journalNow('app', 'error', 'Не удалось подключиться: сервер не отвечает')]);
  });

  // Журнал виден на главном экране, поэтому переключатель вынесен в панель:
  // иначе пришлось бы идти в настройки, чтобы увидеть обе стороны переключения.
  var journalButton = addButton('логи: вкл', function () {
    window.NodewayVpn.setJournalEnabled(!state.journalEnabled);
  });

  function applyJournalButton() {
    journalButton.textContent = state.journalEnabled ? 'логи: вкл' : 'логи: выкл';
  }

  /*
   * Стартовые строки отдаём по событию load, а не по setTimeout.
   *
   * Мок подключается отдельным <script> перед разметкой страницы и выполняется
   * раньше её собственного скрипта: таймер с нулевой задержкой успевает
   * сработать в промежутке между ними, когда window.onJournalReset ещё нет.
   * Событие load приходит после выполнения всех скриптов — это единственная
   * точка, где обработчики журнала гарантированно уже на месте.
   */
  window.addEventListener('load', function () {
    emitJournalReset();
    applyJournalButton();
  });

  applyPanelState();
})();