// Copy buttons and confirmations. Loaded from this server only (the page's security policy forbids inline script).
document.addEventListener('click', async (e) => {
  const btn = e.target.closest('[data-copy]');
  if (!btn) return;
  const box = document.getElementById(btn.getAttribute('data-copy'));
  if (!box) return;
  const label = btn.textContent;
  try {
    await navigator.clipboard.writeText(box.value ?? box.textContent);
    btn.textContent = 'Copied';
  } catch {
    box.select?.();
    btn.textContent = 'Press Ctrl+C';
  }
  setTimeout(() => { btn.textContent = label; }, 1600);
});

// Confirmations, then progress: once a form is really being sent, its button shows a spinner and what is
// happening ("Sending code…"), and further clicks are ignored until the next page arrives.
document.addEventListener('submit', (e) => {
  const form = e.target;
  const msg = form.getAttribute('data-confirm');
  if (msg && !window.confirm(msg)) { e.preventDefault(); return; }
  if (form.dataset.busy) { e.preventDefault(); return; }   // already sending: no double submit
  const btn = e.submitter || form.querySelector('button[type="submit"], button:not([type])');
  form.dataset.busy = 'true';
  if (!btn) return;
  btn.dataset.idleHtml = btn.innerHTML;
  btn.classList.add('is-busy');
  btn.setAttribute('aria-busy', 'true');
  btn.setAttribute('aria-disabled', 'true');
  const label = btn.getAttribute('data-busy-label');
  if (label) {
    const text = document.createElement('span');
    text.textContent = label;
    btn.replaceChildren(text);
  }
});

// Coming back with the browser's Back button shows the page as it was left: make its buttons usable again.
window.addEventListener('pageshow', (e) => {
  if (!e.persisted) return;
  document.querySelectorAll('form[data-busy]').forEach((f) => delete f.dataset.busy);
  document.querySelectorAll('.is-busy').forEach((b) => {
    if (b.dataset.idleHtml) b.innerHTML = b.dataset.idleHtml;
    b.classList.remove('is-busy');
    b.removeAttribute('aria-busy');
    b.removeAttribute('aria-disabled');
  });
});

// Chart tooltips: any element with data-tip-value (and data-tip-title). Shown on hover and keyboard focus
// (a tap focuses on phones); values also stay in the table view, so nothing depends on the tooltip.
(() => {
  let tip = null;
  const show = (el) => {
    if (!tip) {
      tip = document.createElement('div');
      tip.className = 'tip';
      tip.setAttribute('role', 'tooltip');
      document.body.appendChild(tip);
    }
    const value = document.createElement('strong');
    value.textContent = el.getAttribute('data-tip-value');
    const title = document.createElement('span');
    title.textContent = el.getAttribute('data-tip-title') || '';
    tip.replaceChildren(value, title);
    tip.hidden = false;
    const anchor = (el.querySelector('.chart-bar') || el).getBoundingClientRect();
    const col = el.getBoundingClientRect();
    const w = tip.offsetWidth, h = tip.offsetHeight;
    let left = col.left + col.width / 2 - w / 2;
    left = Math.max(8, Math.min(left, document.documentElement.clientWidth - w - 8));
    let top = Math.min(anchor.top, col.bottom - 4) - h - 8;
    if (top < 8) top = col.top + 8;
    tip.style.left = (left + window.scrollX) + 'px';
    tip.style.top = (top + window.scrollY) + 'px';
  };
  const hide = () => { if (tip) tip.hidden = true; };
  const target = (e) => e.target instanceof Element ? e.target.closest('[data-tip-value]') : null;
  document.addEventListener('pointerover', (e) => { const el = target(e); if (el) show(el); });
  document.addEventListener('pointerout', (e) => { const el = target(e); if (el && !el.contains(e.relatedTarget)) hide(); });
  document.addEventListener('focusin', (e) => { const el = target(e); if (el) show(el); else hide(); });
  document.addEventListener('focusout', hide);
  window.addEventListener('scroll', hide, { passive: true });
})();
