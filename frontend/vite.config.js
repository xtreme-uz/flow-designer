import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: './vitest.setup.js',
    globals: false,
    // Node code (the converter) runs fine in jsdom too, so one project covers both
    include: ['src/**/*.test.{js,jsx}'],
  },
  server: {
    proxy: {
      '/api':    { target: 'https://localhost:8443', changeOrigin: true, secure: false },
      '/oauth2': { target: 'https://localhost:8443', changeOrigin: true, secure: false },
      '/login':  { target: 'https://localhost:8443', changeOrigin: true, secure: false },
      '/logout': { target: 'https://localhost:8443', changeOrigin: true, secure: false }
    }
  }
})
