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

document.addEventListener('submit', (e) => {
  const msg = e.target.getAttribute('data-confirm');
  if (msg && !window.confirm(msg)) e.preventDefault();
});
