import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const API_TARGET = 'http://localhost:8080'
const ROUTE_ONLY_PRELOADS = [
  'react-markdown',
  'recharts',
  '@codemirror',
  'microsoft.cognitiveservices.speech',
]

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: 'build/dist',
    modulePreload: {
      resolveDependencies(_filename, deps, context) {
        if (context.hostType !== 'html') return deps
        return deps.filter(dep => !ROUTE_ONLY_PRELOADS.some(chunk => dep.includes(chunk)))
      },
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: API_TARGET,
        secure: false,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.setHeader('X-Forwarded-Proto', 'https');
          });
          // SSE passthrough: preserve Content-Type and prevent buffering
          proxy.on('proxyRes', (proxyRes, _req, res) => {
            const ct = proxyRes.headers['content-type'];
            if (ct && ct.startsWith('text/event-stream')) {
              // Explicitly re-set header – http-proxy may strip it before pipe
              res.setHeader('Content-Type', ct);
              res.setHeader('Cache-Control', 'no-cache');
              res.setHeader('Connection', 'keep-alive');
              res.flushHeaders();
            }
          });
        },
      },
      '/.well-known': {
        target: API_TARGET,
        secure: false,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.setHeader('X-Forwarded-Proto', 'https');
          });
        },
      }
    },
  },
})
