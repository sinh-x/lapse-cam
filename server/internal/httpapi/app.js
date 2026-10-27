'use strict';
let cursor = '', next = '', csrf = '';
const element = (tag, text) => { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; return e; };
async function request(path, options) { const r = await fetch(path, options); if (!r.ok) throw new Error(r.status === 401 ? 'Log in to view the archive.' : `Request failed (${r.status}); no changes acknowledged.`); return r.json(); }
async function status() { try { const s = await request('/v1/status'); document.getElementById('status').textContent = s.lowStorage ? 'Warning: archive filesystem has less than 10% free space. No automatic deletion or reserve is applied.' : `Server reached ${new Date(s.serverTime).toLocaleString()}`; } catch (e) { document.getElementById('status').textContent = e.message; } }
async function load() {
 try {
  const q = new URLSearchParams(new FormData(document.getElementById('filters')));
  for (const [k,v] of [...q]) { if (!v) q.delete(k); else if (k === 'since' || k === 'until') q.set(k, String(new Date(v + (k === 'until' ? 'T23:59:59.999Z' : 'T00:00:00Z')).getTime())); }
  if (cursor) q.set('after', cursor);
  const page = await request('/v1/history?' + q);
  const records = document.getElementById('records'); records.replaceChildren();
  for (const r of page.records) {
   const box = element('article'); box.append(element('h3', r.fields.title.value));
   box.append(element('p', `${r.device} / ${r.id} · ${r.source.state}${r.source.archived ? ' · archived' : ''}${r.source.deleted ? ' · source deleted, archive retained' : ''} · last sync ${new Date(r.syncedAt).toLocaleString()}`));
   for (const [name,field] of Object.entries(r.fields)) { box.append(element('p', `${name}: ${field.value}`)); if (field.variants.length) box.append(element('p', `Unresolved conflict: ${field.variants.map(v => v.value).join(' | ')}. Resolve on an enrolled phone.`)); }
   const details = element('details'); details.append(element('summary', `Timing and exact recording associations · planned ${r.source.plannedMinutes} minutes`)); details.append(element('pre', JSON.stringify({timing:r.source.timing, links:r.source.links},null,2))); box.append(details); records.append(box);
  }
  if (!page.records.length) records.append(element('p', 'No work records on this page.'));
  const videos = document.getElementById('videos'); videos.replaceChildren();
  for (const v of page.videos) {
   const box = element('article'); box.append(element('h3', `${v.device} / ${v.id}`)); box.append(element('p', `Recording: ${v.recording || 'unattributed'} · ${v.size} bytes · SHA-256 ${v.sha256}${v.missing ? ' · source missing, archive retained' : ''}`));
   const path = '/v1/media/' + encodeURIComponent(v.device) + '/' + encodeURIComponent(v.id);
   const player = element('video'); player.controls = true; player.preload = 'none'; player.src = path; box.append(player);
   const link = element('a', 'Download MP4'); link.href = path; link.download = v.id + '.mp4'; box.append(link); videos.append(box);
  }
  next = page.next; document.getElementById('next').disabled = !next; await status();
 } catch(e) { document.getElementById('status').textContent = e.message; }
}
document.getElementById('filters').addEventListener('submit', e => { e.preventDefault(); cursor=''; load(); });
document.getElementById('next').addEventListener('click', () => { cursor=next; load(); });
document.getElementById('logout').addEventListener('click', async () => { try { if (!csrf) csrf=(await request('/v1/session')).csrf; await request('/logout', {method:'POST',headers:{'X-CSRF-Token':csrf}}); location.assign('/login'); } catch(e) { document.getElementById('status').textContent=e.message; } });
load(); setInterval(status, 30000);
