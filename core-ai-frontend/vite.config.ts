import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: 'build/dist',
  },
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:3824',
      '/.well-known': 'http://localhost:3824',
      '/message': 'http://localhost:3824',
      '/tasks': 'http://localhost:3824',
      '/v1': 'http://localhost:8080',
    },
  },
})
