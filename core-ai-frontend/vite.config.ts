import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const API_TARGET = 'http://localhost:8080'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: 'build/dist',
  },
  server: {
    port: 3000,
    proxy: {
      '/api': API_TARGET,
      '/.well-known': API_TARGET,
      '/message': API_TARGET,
      '/tasks': API_TARGET,
      '/v1': API_TARGET,
    },
  },
})
