import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import App from './App';

// When server redeploys, old chunk hashes become 404.
// Vite fires preloadError for dynamic import failures — auto-reload to get new chunks.
window.addEventListener('vite:preloadError', (event) => {
  console.warn('Chunk load failed (likely server redeployed), reloading...', event);
  window.location.reload();
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
