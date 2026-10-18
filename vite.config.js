import { defineConfig } from 'vite';

// Capacitor loads assets from a local WebView origin. Relative asset URLs
// keep the generated HTML working inside the APK as well as in browser preview.
export default defineConfig({
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    assetsDir: 'assets',
  },
});
