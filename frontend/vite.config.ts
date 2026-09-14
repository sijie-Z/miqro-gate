import { fileURLToPath, URL } from 'node:url';

import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  build: {
    // Split the framework and the icon set so the entry chunk stays small
    // and the browser can cache vendor code independently. Function form:
    // Rolldown (Vite 8+) requires it, Rollup (Vite 6) accepts both.
    chunkSizeWarningLimit: 1300,
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (
            id.includes('node_modules/vue/') ||
            id.includes('node_modules/vue-router/') ||
            id.includes('node_modules/pinia/') ||
            id.includes('node_modules/@vue/')
          ) {
            return 'vue';
          }
          if (id.includes('node_modules/tdesign-icons-vue-next/')) {
            return 'tdesign-icons';
          }
          return undefined;
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
  preview: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
