import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.vitest.js']
  },
  server: {
    port: 5173,
    host: '0.0.0.0'
  }
})
