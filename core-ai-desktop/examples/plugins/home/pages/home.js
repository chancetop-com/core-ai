/*
 * My Home plugin page. Everything here is untrusted page code: it only talks to the host through
 * window.coreaiPlugin, renders user data as text, and keeps a small offline snapshot in plugin storage.
 */
(() => {
  'use strict';

  const api = window.coreaiPlugin;
  const CACHE_KEY = 'home.cache.v1';
  const CACHE_LIMIT = 50;

  const state = {
    signedIn: false,
    serverHost: null,
    userName: null,
    reports: [],
    reportTotal: 0,
    selectedId: null,
    detail: null,
    data: null,
    rangeDays: 0,
    todos: [],
    digest: null,
    lastSyncAt: null,
    offline: false,
    failed: {},
  };

  const els = {};

  class HomeError extends Error {
    constructor(code, message) {
      super(message);
      this.code = code;
    }
  }

  function $(id) {
    return document.getElementById(id);
  }

  function text(value, fallback = '') {
    return value == null || value === '' ? fallback : String(value);
  }

  function node(tag, className, value) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (value !== undefined && value !== null) element.textContent = String(value);
    return element;
  }

  function formatTime(value) {
    if (!value) return '';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    return date.toLocaleString(undefined, {
      year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    });
  }

  function formatDay(value) {
    if (!value) return '';
    const date = new Date(`${value}T00:00:00`);
    if (Number.isNaN(date.getTime())) return String(value);
    return date.toLocaleDateString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit' });
  }

  function formatNumber(value) {
    if (value == null || value === '') return '—';
    const number = Number(value);
    if (!Number.isFinite(number)) return String(value);
    return number.toLocaleString(undefined, { maximumFractionDigits: 2 });
  }

  async function call(action) {
    let envelope;
    try {
      envelope = await action();
    } catch (error) {
      throw new HomeError('bridge_error', error && error.message ? error.message : 'bridge call failed');
    }
    if (!envelope || envelope.ok !== true) {
      const detail = (envelope && envelope.error) || {};
      throw new HomeError(detail.code || 'server_error', detail.message || 'request failed');
    }
    return envelope.value;
  }

  function errorText(code) {
    switch (code) {
      case 'not_signed_in':
        return 'Not signed in to core-ai-server. Sign in from the app, then refresh.';
      case 'forbidden':
        return 'This account does not have permission to view this data.';
      case 'offline':
        return 'Cannot reach core-ai-server.';
      case 'not_found':
        return 'The item no longer exists.';
      case 'invalid_request':
        return 'The request was rejected.';
      case 'rate_limited':
        return 'Too many changes at once. Try again in a minute.';
      default:
        return 'Something went wrong while loading.';
    }
  }

  async function boot() {
    els.banner = $('banner');
    els.reportList = $('report-list');
    els.reportState = $('report-state');
    els.reportCount = $('report-count');
    els.detailTitle = $('detail-title');
    els.detailRanges = $('detail-ranges');
    els.detailBody = $('detail-body');
    els.todoCount = $('todo-count');
    els.todoState = $('todo-state');
    els.todoActive = $('todo-active');
    els.todoDone = $('todo-done');
    els.todoDoneGroup = $('todo-done-group');
    els.todoDoneCount = $('todo-done-count');
    els.digest = $('digest');
    els.form = $('todo-form');
    els.todoTitle = $('todo-title');
    els.todoSubmit = $('todo-submit');
    els.refresh = $('refresh');
    els.greeting = $('greeting');
    els.subtitle = $('subtitle');
    els.avatar = $('avatar');

    try {
      const context = await api.context();
      document.documentElement.dataset.theme = context.theme === 'dark' ? 'dark' : 'light';
    } catch {
      document.documentElement.dataset.theme = 'light';
    }

    els.refresh.addEventListener('click', () => void loadAll(true));
    els.form.addEventListener('submit', (event) => {
      event.preventDefault();
      void addTodo();
    });
    els.detailRanges.addEventListener('click', (event) => {
      const target = event.target.closest('button[data-range]');
      if (!target) return;
      state.rangeDays = Number(target.dataset.range);
      for (const button of els.detailRanges.querySelectorAll('button')) button.classList.toggle('on', button === target);
      if (state.selectedId) void selectReport(state.selectedId);
    });

    await loadAll(false);
  }

  async function loadAll(manual) {
    els.refresh.disabled = true;
    try {
      const status = await call(() => api.home.status());
      state.signedIn = status.signedIn === true;
      state.serverHost = status.serverHost || null;
      state.userName = status.userName || null;
      renderIdentity();
      if (!state.signedIn) {
        hideBanner();
        renderSignedOut();
        return;
      }
      await Promise.allSettled([loadReports(), loadTodos(), loadDigest()]);
      state.lastSyncAt = new Date().toISOString();
      await saveCache();
      renderIdentity();
      if (manual) {
        state.offline = false;
        hideBanner();
      }
      if (state.failed.reports || state.failed.todos) {
        await applyOfflineFallback();
      } else {
        hideBanner();
      }
    } catch (error) {
      const code = error instanceof HomeError ? error.code : 'server_error';
      showBanner(`${errorText(code)}${code === 'not_signed_in' ? '' : ' Showing the last saved snapshot.'}`, true);
      await applyOfflineFallback(true);
    } finally {
      els.refresh.disabled = false;
    }
  }

  function renderIdentity() {
    const name = state.userName || (state.signedIn ? 'Signed in' : 'Not signed in');
    els.greeting.textContent = state.signedIn && state.userName ? `Hi, ${state.userName}` : 'My Home';
    els.avatar.textContent = (state.userName || '·').trim().charAt(0).toUpperCase() || '·';
    const parts = [];
    if (state.serverHost) parts.push(state.serverHost);
    if (state.lastSyncAt) parts.push(`synced ${formatTime(state.lastSyncAt)}`);
    if (state.offline) parts.push('offline snapshot');
    els.subtitle.textContent = parts.join(' · ') || 'Connecting…';
  }

  function renderSignedOut() {
    state.reports = [];
    state.todos = [];
    state.reportTotal = 0;
    renderReports();
    renderTodos();
    renderDigest();
    showBanner('Sign in to core-ai-server from the app to see your reports and todos.', false);
  }

  function showBanner(message, isError) {
    els.banner.hidden = false;
    els.banner.textContent = message;
    els.banner.className = `banner${isError ? '' : ' info'}`;
  }

  function hideBanner() {
    els.banner.hidden = true;
  }

  async function loadReports() {
    state.failed.reports = null;
    try {
      const result = await call(() => api.home.reports({ limit: CACHE_LIMIT }));
      state.reports = Array.isArray(result.reports) ? result.reports : [];
      state.reportTotal = typeof result.total === 'number' ? result.total : state.reports.length;
      renderReports();
      if (!state.selectedId && state.reports.length) {
        await selectReport(state.reports[0].id);
      }
    } catch (error) {
      state.failed.reports = error instanceof HomeError ? error.code : 'server_error';
      renderReports();
    }
  }

  function renderReports() {
    els.reportList.replaceChildren();
    els.reportCount.textContent = state.reports.length ? String(state.reportTotal || state.reports.length) : '';
    if (state.failed.reports) {
      els.reportState.className = 'state error';
      els.reportState.textContent = errorText(state.failed.reports);
      return;
    }
    if (!state.reports.length) {
      els.reportState.className = 'state';
      els.reportState.textContent = state.signedIn ? 'No dataset reports yet.' : 'Sign in to load reports.';
      return;
    }
    els.reportState.textContent = '';
    for (const report of state.reports) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `report${report.id === state.selectedId ? ' on' : ''}`;
      button.dataset.id = report.id;
      const title = node('span', 'title', text(report.title, '(untitled report)'));
      if (report.displayType) title.append(node('span', 'badge', report.displayType));
      const meta = node('span', 'meta');
      const bits = [];
      if (report.datasetName) bits.push(report.datasetName);
      if (report.updatedAt) bits.push(`updated ${formatTime(report.updatedAt)}`);
      meta.textContent = bits.join(' · ') || '—';
      button.append(title, meta);
      button.addEventListener('click', () => void selectReport(report.id));
      els.reportList.append(button);
    }
  }

  async function selectReport(id) {
    state.selectedId = id;
    state.detail = null;
    state.data = null;
    renderReports();
    renderDetail();
    const report = state.reports.find((item) => item.id === id);
    els.detailTitle.textContent = report && report.title ? report.title : 'Report preview';
    try {
      state.detail = await call(() => api.home.report(id));
      renderDetail();
      await loadReportData();
    } catch (error) {
      const code = error instanceof HomeError ? error.code : 'server_error';
      state.failed.detail = code;
      renderDetail();
    }
  }

  async function loadReportData() {
    if (!state.selectedId) return;
    state.failed.detail = null;
    renderDetail();
    try {
      const options = state.rangeDays > 0 ? { rangeKind: 'ROLLING_DAYS', days: state.rangeDays } : {};
      state.data = await call(() => api.home.reportData(state.selectedId, options));
    } catch (error) {
      state.failed.detail = error instanceof HomeError ? error.code : 'server_error';
    }
    renderDetail();
  }

  function renderDetail() {
    els.detailRanges.hidden = !state.detail;
    els.detailBody.replaceChildren();
    if (!state.selectedId) {
      els.detailBody.append(node('div', 'state', 'Select a report to preview.'));
      return;
    }
    if (!state.detail) {
      if (state.failed.detail) {
        els.detailBody.append(node('div', 'state error', errorText(state.failed.detail)));
      } else {
        els.detailBody.append(node('div', 'state', 'Loading…'));
      }
      return;
    }

    const report = state.detail;
    const meta = node('div', 'meta-row');
    if (report.datasetName) meta.append(node('span', null, `Dataset: ${report.datasetName}`));
    if (report.revision != null) meta.append(node('span', null, `Revision ${report.revision}`));
    if (report.updatedAt) meta.append(node('span', null, `Updated ${formatTime(report.updatedAt)}`));
    if (report.description) meta.append(node('span', null, report.description));
    els.detailBody.append(meta);

    if (state.failed.detail) {
      els.detailBody.append(node('div', 'state error', errorText(state.failed.detail)));
      return;
    }
    if (!state.data) {
      els.detailBody.append(node('div', 'state', 'Loading data…'));
      return;
    }
    const data = state.data;
    if (data.status === 'NO_DATA' || !data.columns.length) {
      els.detailBody.append(node('div', 'state', 'No data in this range.'));
      appendDataMeta(data);
      return;
    }

    const displayType = (report.displayType || 'TABLE').toUpperCase();
    if (displayType === 'METRIC') {
      const metric = node('div', 'metric');
      metric.append(node('div', 'value', formatNumber(data.rows[0] ? data.rows[0][0] : null)));
      metric.append(node('div', 'label', data.columns[0] ? data.columns[0].name : ''));
      els.detailBody.append(metric);
    } else if (displayType === 'BAR' || displayType === 'LINE') {
      els.detailBody.append(displayType === 'BAR' ? renderBars(data) : renderLine(data));
    } else {
      els.detailBody.append(renderTable(data));
    }
    appendDataMeta(data);
  }

  function renderTable(data) {
    const wrap = node('div', 'table-wrap');
    const table = document.createElement('table');
    const head = document.createElement('thead');
    const headRow = document.createElement('tr');
    for (const column of data.columns) headRow.append(node('th', null, column.name));
    head.append(headRow);
    table.append(head);
    const body = document.createElement('tbody');
    for (const row of data.rows) {
      const tr = document.createElement('tr');
      for (let index = 0; index < data.columns.length; index++) {
        const cell = row[index];
        tr.append(node('td', null, cell == null ? '—' : cell));
      }
      body.append(tr);
    }
    table.append(body);
    wrap.append(table);
    return wrap;
  }

  function seriesOf(data) {
    const points = [];
    for (const row of data.rows) {
      const raw = row[1] == null ? row[0] : row[1];
      const value = Number(raw);
      if (!Number.isFinite(value)) continue;
      points.push({ label: text(row[0], '—'), value });
    }
    return points;
  }

  function renderBars(data) {
    const points = seriesOf(data);
    const root = node('div', 'bars');
    if (!points.length) return node('div', 'state', 'No numeric series to chart.');
    const max = Math.max(...points.map((point) => Math.abs(point.value)), 1);
    for (const point of points.slice(0, 40)) {
      const row = node('div', 'bar-row');
      row.append(node('span', null, point.label));
      const track = node('div', 'bar-track');
      const fill = node('div', 'bar-fill');
      fill.style.width = `${Math.max(2, Math.round((Math.abs(point.value) / max) * 100))}%`;
      track.append(fill);
      row.append(track);
      row.append(node('span', 'bar-value', formatNumber(point.value)));
      root.append(row);
    }
    return root;
  }

  function renderLine(data) {
    const points = seriesOf(data);
    if (!points.length) return node('div', 'state', 'No numeric series to chart.');
    const width = 640;
    const height = 180;
    const padding = 24;
    const values = points.map((point) => point.value);
    const max = Math.max(...values);
    const min = Math.min(...values, 0);
    const span = max - min || 1;
    const step = points.length > 1 ? (width - padding * 2) / (points.length - 1) : 0;
    const coords = points.map((point, index) => {
      const x = padding + step * index;
      const y = height - padding - ((point.value - min) / span) * (height - padding * 2);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    });
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'spark');
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    svg.setAttribute('role', 'img');
    const polyline = document.createElementNS('http://www.w3.org/2000/svg', 'polyline');
    polyline.setAttribute('points', coords.join(' '));
    polyline.setAttribute('fill', 'none');
    polyline.setAttribute('stroke', 'currentColor');
    polyline.setAttribute('stroke-width', '2');
    svg.append(polyline);
    const caption = node('div', 'meta-row');
    caption.append(node('span', null, points[0].label));
    caption.append(node('span', null, '→'));
    caption.append(node('span', null, points[points.length - 1].label));
    const root = document.createElement('div');
    root.append(svg, caption);
    return root;
  }

  function appendDataMeta(data) {
    const meta = node('div', 'meta-row');
    if (data.matchedRecordCount != null) meta.append(node('span', null, `${formatNumber(data.matchedRecordCount)} matched`));
    if (data.evaluatedAt) meta.append(node('span', null, `evaluated ${formatTime(data.evaluatedAt)}`));
    if (data.freshnessStatus) meta.append(node('span', null, data.freshnessStatus.toLowerCase().replace(/_/g, ' ')));
    if (data.hasMore) meta.append(node('span', null, 'partial page'));
    if (meta.childElementCount) els.detailBody.append(meta);
  }

  async function loadTodos() {
    state.failed.todos = null;
    try {
      const result = await call(() => api.home.todos());
      state.todos = Array.isArray(result.todos) ? result.todos : [];
      renderTodos();
    } catch (error) {
      state.failed.todos = error instanceof HomeError ? error.code : 'server_error';
      renderTodos();
    }
  }

  async function loadDigest() {
    try {
      state.digest = await call(() => api.home.todoDigest());
    } catch {
      state.digest = null;
    }
    renderDigest();
  }

  function renderTodos() {
    els.todoActive.replaceChildren();
    els.todoDone.replaceChildren();
    const active = state.todos.filter((todo) => !todo.completed);
    const done = state.todos.filter((todo) => todo.completed);
    els.todoCount.textContent = state.todos.length ? `${active.length} open` : '';

    if (state.failed.todos) {
      els.todoState.className = 'state error';
      els.todoState.textContent = errorText(state.failed.todos);
    } else if (!state.todos.length) {
      els.todoState.className = 'state';
      els.todoState.textContent = state.signedIn ? 'Nothing on the list yet.' : 'Sign in to load todos.';
    } else {
      els.todoState.textContent = '';
    }

    for (const todo of active) els.todoActive.append(todoRow(todo));
    els.todoDoneGroup.hidden = !done.length;
    els.todoDoneCount.textContent = String(done.length);
    for (const todo of done) els.todoDone.append(todoRow(todo));
  }

  function todoRow(todo) {
    const row = node('div', `todo${todo.completed ? ' done' : ''}`);
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = todo.completed === true;
    checkbox.title = todo.completed ? 'Mark as open' : 'Mark as done';
    checkbox.addEventListener('change', () => void setCompleted(todo, checkbox.checked));

    const main = node('div', 'todo-main');
    const title = node('div', 'todo-title');
    title.append(document.createTextNode(todo.title));
    if (todo.source === 'AUTO') title.append(node('span', 'badge auto', 'Auto'));
    if (todo.priority === 'high') title.append(node('span', 'badge', 'High'));
    main.append(title);

    const sub = [];
    if (todo.originDate) sub.push(`from ${formatDay(todo.originDate)} sessions`);
    if (todo.dueDate) sub.push(`due ${formatTime(todo.dueDate)}`);
    if (todo.description) sub.push(todo.description);
    if (sub.length) main.append(node('div', 'todo-sub', sub.join(' · ')));

    const remove = node('button', 'icon-button', '✕');
    remove.type = 'button';
    remove.title = 'Delete';
    remove.addEventListener('click', () => void deleteTodo(todo));

    row.append(checkbox, main, remove);
    return row;
  }

  async function addTodo() {
    const title = els.todoTitle.value.trim();
    if (!title) return;
    els.todoSubmit.disabled = true;
    try {
      const todo = await call(() => api.home.createTodo({ title }));
      els.todoTitle.value = '';
      state.todos.push(todo);
      renderTodos();
      hideBanner();
    } catch (error) {
      showBanner(errorText(error instanceof HomeError ? error.code : 'server_error'), true);
    } finally {
      els.todoSubmit.disabled = false;
      els.todoTitle.focus();
    }
  }

  async function setCompleted(todo, completed) {
    try {
      const updated = await call(() => api.home.updateTodo(todo.id, { completed }));
      replaceTodo(updated);
      hideBanner();
    } catch (error) {
      showBanner(errorText(error instanceof HomeError ? error.code : 'server_error'), true);
      renderTodos();
    }
  }

  async function deleteTodo(todo) {
    try {
      await call(() => api.home.deleteTodo(todo.id));
      state.todos = state.todos.filter((item) => item.id !== todo.id);
      renderTodos();
      hideBanner();
    } catch (error) {
      showBanner(errorText(error instanceof HomeError ? error.code : 'server_error'), true);
    }
  }

  function replaceTodo(updated) {
    state.todos = state.todos.map((item) => (item.id === updated.id ? updated : item));
    renderTodos();
  }

  function renderDigest() {
    els.digest.replaceChildren();
    if (!state.digest) return;
    const digest = state.digest;
    if (digest.status === 'NOT_RUN') {
      els.digest.append(document.createTextNode(`Daily summary for ${formatDay(digest.date)} has not run yet.`));
      return;
    }
    if (digest.status === 'FAILED') {
      els.digest.append(node('span', 'fail', `Daily summary failed for ${formatDay(digest.date)}: ${text(digest.error, 'unknown error')}`));
      return;
    }
    if (digest.status === 'RUNNING') {
      els.digest.append(document.createTextNode(`Daily summary for ${formatDay(digest.date)} is running…`));
      return;
    }
    const parts = [`Daily summary ${formatDay(digest.date)}: ${digest.todoCount || 0} new`];
    if (digest.skippedCount) parts.push(`${digest.skippedCount} already existed`);
    if (digest.sessionCount != null) parts.push(`${digest.sessionCount} sessions`);
    if (digest.model) parts.push(digest.model);
    if (digest.completedAt) parts.push(formatTime(digest.completedAt));
    els.digest.append(document.createTextNode(parts.join(' · ')));
  }

  async function saveCache() {
    if (!state.signedIn) return;
    const snapshot = {
      savedAt: new Date().toISOString(),
      serverHost: state.serverHost,
      reports: state.reports.slice(0, CACHE_LIMIT).map((report) => ({
        id: report.id, title: report.title, displayType: report.displayType, datasetName: report.datasetName, updatedAt: report.updatedAt,
      })),
      todos: state.todos.slice(0, 200).map((todo) => ({
        id: todo.id, title: todo.title, completed: todo.completed, priority: todo.priority, source: todo.source,
        originDate: todo.originDate, description: todo.description, dueDate: todo.dueDate,
      })),
    };
    try {
      await api.storage.set(CACHE_KEY, JSON.stringify(snapshot));
    } catch {
      // the cache is best effort
    }
  }

  async function applyOfflineFallback(force) {
    state.offline = true;
    let cached = null;
    try {
      const stored = await api.storage.get(CACHE_KEY);
      cached = stored && stored.value ? JSON.parse(stored.value) : null;
    } catch {
      cached = null;
    }
    if (!cached) {
      if (!force) hideBanner();
      renderIdentity();
      return;
    }
    if (state.failed.reports || !state.reports.length) {
      state.reports = Array.isArray(cached.reports) ? cached.reports : [];
      state.reportTotal = state.reports.length;
      renderReports();
      if (!state.selectedId && state.reports.length) {
        state.selectedId = state.reports[0].id;
        state.detail = null;
        state.data = null;
        renderReports();
        renderDetail();
      }
    }
    if (state.failed.todos || !state.todos.length) {
      state.todos = Array.isArray(cached.todos) ? cached.todos : [];
      renderTodos();
    }
    state.lastSyncAt = cached.savedAt || state.lastSyncAt;
    renderIdentity();
    showBanner(`Showing the snapshot saved ${formatTime(cached.savedAt)}. Reconnect to refresh.`, false);
  }

  boot().catch((error) => {
    const banner = $('banner');
    if (banner) {
      banner.hidden = false;
      banner.className = 'banner';
      banner.textContent = `Failed to start: ${error && error.message ? error.message : 'unknown error'}`;
    }
  });
})();
