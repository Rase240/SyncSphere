// Home page: list documents, create a new one, remember the display name.
import { fetchJson, formatTime, getDisplayName, NAME_PATTERN, setDisplayName, showInstanceBadge } from './common.js';

const els = {
  instance: document.getElementById('instance-badge'),
  nameInput: document.getElementById('display-name'),
  createForm: document.getElementById('create-form'),
  titleInput: document.getElementById('new-title'),
  createError: document.getElementById('create-error'),
  list: document.getElementById('document-list'),
  listStatus: document.getElementById('list-status'),
  openForm: document.getElementById('open-form'),
  openId: document.getElementById('open-id'),
};

function documentUrl(id) {
  return `/document.html?id=${encodeURIComponent(id)}`;
}

async function loadDocuments() {
  try {
    const documents = await fetchJson('/api/documents');
    els.list.replaceChildren();
    els.listStatus.textContent = documents.length === 0 ? 'No documents yet. Create the first one above.' : '';
    for (const doc of documents) {
      const row = document.createElement('li');
      const link = document.createElement('a');
      link.href = documentUrl(doc.id);
      link.textContent = doc.title; // textContent, never innerHTML: titles are user input
      const meta = document.createElement('span');
      meta.className = 'meta';
      meta.textContent = `revision ${doc.revision} · edited ${formatTime(doc.updatedAt)}`;
      row.append(link, meta);
      els.list.append(row);
    }
  } catch (error) {
    els.listStatus.textContent = `Could not load documents: ${error.message}`;
  }
}

els.nameInput.value = getDisplayName();
els.nameInput.addEventListener('change', () => {
  const name = els.nameInput.value.trim();
  if (NAME_PATTERN.test(name)) {
    setDisplayName(name);
  }
});

els.createForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  els.createError.textContent = '';
  try {
    const created = await fetchJson('/api/documents', {
      method: 'POST',
      body: JSON.stringify({ title: els.titleInput.value }),
    });
    window.location.href = documentUrl(created.id);
  } catch (error) {
    els.createError.textContent = error.message;
  }
});

els.openForm.addEventListener('submit', (event) => {
  event.preventDefault();
  const id = els.openId.value.trim();
  if (id) {
    window.location.href = documentUrl(id);
  }
});

showInstanceBadge(els.instance);
loadDocuments();
