import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const API_TARGET = 'https://localhost:8443'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: 'build/dist',
  },
  server: {
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
