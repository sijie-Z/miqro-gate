import { fileURLToPath, URL } from 'node:url';

import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  build: {
    // tdesign-vue-next is a large library; it is its own cacheable chunk,
    // so the warning threshold targets the entry bundle only.
    chunkSizeWarningLimit: 1300,
    rollupOptions: {
      output: {
        // Split the framework and component libraries so the entry chunk
        // stays small and the browser can cache vendor code independently.
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          tdesign: ['tdesign-vue-next'],
          'tdesign-icons': ['tdesign-icons-vue-next'],
        },
      },
    },
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    // Warm the entry transforms so the first page load never races Vite's
    // cold-start module graph (Playwright sees "预加载桥接不可用" otherwise).
    warmup: {
      clientFiles: [
        './src/main.ts',
        './src/App.vue',
        './src/styles/tokens.css',
        './src/styles/global.css',
      ],
    },
    proxy: {
      // Management API lives on the Control Plane (8080); the Gateway
      // data plane (8081) is not proxied by the dev server.
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
