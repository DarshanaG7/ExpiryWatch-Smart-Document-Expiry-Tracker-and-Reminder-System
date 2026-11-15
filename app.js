const $ = id => document.getElementById(id);
const API = '/api';
let docs = [], filter = 'all', editingId = null, defaultReminder = 30;

const LABEL = { expired: 'Expired', critical: 'Critical', warning: 'Warning', upcoming: 'Upcoming', safe: 'Safe' };

const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

async function api(path, options = {}) {
  const res = await fetch(API + path, { headers: { 'Content-Type': 'application/json' }, ...options });
  if (res.status === 204) return null;
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || 'Request failed');
  return data;
}

function toast(msg) {
  const t = $('toast');
  t.textContent = msg; t.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.remove('show'), 2200);
}

function timeLeft(d) {
  if (d < 0) return `${-d} day${d === -1 ? '' : 's'} overdue`;
  if (d === 0) return 'Expires today';
  return `${d} day${d === 1 ? '' : 's'} left`;
}

async function refresh() {
  const [list, dash, settings] = await Promise.all([api('/documents'), api('/dashboard'), api('/settings')]);
  docs = list;
  defaultReminder = settings.defaultReminderDays;
  if (document.activeElement !== $('defaultReminder')) $('defaultReminder').value = defaultReminder;
  if (!editingId && !$('reminder').value) $('reminder').value = defaultReminder;

  $('cExpired').textContent = dash.expired;
  $('c7').textContent = dash.within7;
  $('c15').textContent = dash.within15;
  $('c30').textContent = dash.within30;
  $('cSafe').textContent = dash.safe;
  document.querySelectorAll('.tile').forEach(t => t.classList.toggle('hot', Number(t.querySelector('.num').textContent) > 0 && !t.classList.contains('t-safe')));

  renderAlerts();
  renderTable();
}

function renderAlerts() {
  const alerts = docs.filter(d => d.alert);
  $('alerts').innerHTML = alerts.length
    ? alerts.map(d => `
      <li class="s-${d.status}" style="--c: var(--${d.status})">
        <div><strong>${esc(d.name)}</strong><small>${esc(d.type)} · expires ${d.expiryDate}</small></div>
        <span class="when">${timeLeft(d.daysLeft)}</span>
      </li>`).join('')
    : '<li class="none"><div><strong>You\'re all caught up</strong><small>No document is inside its reminder period.</small></div></li>';
}

function renderTable() {
  const shown = docs.filter(d => filter === 'all' || d.status === filter);
  $('empty').hidden = shown.length > 0;
  $('rows').innerHTML = shown.map(d => `
    <tr>
      <td><strong>${esc(d.name)}</strong><small>${esc(d.type)}</small></td>
      <td>${d.expiryDate}</td>
      <td>${timeLeft(d.daysLeft)}</td>
      <td><span class="badge s-${d.status}">${LABEL[d.status]}</span></td>
      <td>${d.reminderDays} days before</td>
      <td class="act">
        <button class="btn small" data-edit="${d.id}">Edit</button>
        <button class="btn small danger" data-del="${d.id}">Delete</button>
      </td>
    </tr>`).join('');
}

function resetForm() {
  editingId = null;
  $('docForm').reset();
  $('reminder').value = defaultReminder;
  $('formTitle').textContent = 'Add a document';
  $('submitBtn').textContent = 'Add document';
  $('cancelEdit').hidden = true;
  $('formError').textContent = '';
}

$('docForm').addEventListener('submit', async e => {
  e.preventDefault();
  $('formError').textContent = '';
  const body = {
    name: $('name').value, type: $('type').value,
    expiryDate: $('expiry').value, reminderDays: Number($('reminder').value) || defaultReminder
  };
  try {
    await api(editingId ? `/documents/${editingId}` : '/documents', { method: editingId ? 'PUT' : 'POST', body: JSON.stringify(body) });
    toast(editingId ? 'Document updated' : 'Document added');
    resetForm();
    refresh();
  } catch (err) { $('formError').textContent = err.message; }
});

$('cancelEdit').addEventListener('click', resetForm);

$('rows').addEventListener('click', async e => {
  const edit = e.target.dataset.edit, del = e.target.dataset.del;
  if (edit) {
    const d = docs.find(x => x.id == edit);
    editingId = d.id;
    $('name').value = d.name; $('type').value = d.type; $('expiry').value = d.expiryDate; $('reminder').value = d.reminderDays;
    $('formTitle').textContent = 'Edit document';
    $('submitBtn').textContent = 'Save changes';
    $('cancelEdit').hidden = false;
    $('name').focus();
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
  if (del && confirm('Delete this document?')) {
    await api(`/documents/${del}`, { method: 'DELETE' });
    toast('Document deleted');
    refresh();
  }
});

$('filters').addEventListener('click', e => {
  if (!e.target.dataset.f) return;
  filter = e.target.dataset.f;
  document.querySelectorAll('.chip').forEach(c => c.classList.toggle('active', c === e.target));
  renderTable();
});

$('saveSettings').addEventListener('click', async () => {
  try {
    await api('/settings', { method: 'PUT', body: JSON.stringify({ defaultReminderDays: Number($('defaultReminder').value) }) });
    toast('Default reminder saved');
    refresh();
  } catch (err) { toast(err.message); }
});

$('today').textContent = new Date().toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
refresh();
setInterval(refresh, 60000);
