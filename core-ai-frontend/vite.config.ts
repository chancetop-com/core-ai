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
    host: '0.0.0.0',
    port: 3000,
    proxy: {
      '/api': {
        target: API_TARGET,
        secure: false,
      },
      '/.well-known': {
        target: API_TARGET,
        secure: false,
      },
      '/message': {
        target: API_TARGET,
        secure: false,
      },
      '/tasks': {
        target: API_TARGET,
        secure: false,
      },
      '/v1': {
        target: API_TARGET,
        secure: false,
      },
    },
  },
})
