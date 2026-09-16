import { createApp } from 'vue';
import { createPinia } from 'pinia';
import '@/styles/tokens.css';
import '@/styles/global.css';
import '@/styles/design-tokens.css';
import '@/styles/design-base.css';

import App from './App.vue';
import router from './router';
import { installChunkReload, isChunkLoadError, reloadForChunkError } from '@/utils/chunk-reload';

const app = createApp(App);

app.use(createPinia());
app.use(router);

// #663: recover from post-deploy lazy-chunk 404s with a single hard reload
// (preload failures plus the router's own dynamic-import error path).
installChunkReload();
router.onError((error) => {
  if (isChunkLoadError(error)) reloadForChunkError();
});

app.mount('#app');
