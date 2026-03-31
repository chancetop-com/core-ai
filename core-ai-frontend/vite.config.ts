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
      '/api': 'http://localhost:8080',
      '/.well-known': 'http://localhost:9527',
      '/message': 'http://localhost:9527',
      '/tasks': 'http://localhost:9527',
      '/v1': 'http://localhost:8080',
    },
  },
})
