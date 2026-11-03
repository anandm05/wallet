import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Port 3000 matches the callback URL registered on the Cognito `wallet-ui`
// app client. BASE lets the same build be served from a sub-path (GitHub Pages).
export default defineConfig({
  plugins: [react()],
  base: process.env.BASE ?? '/',
  server: { port: 3000, strictPort: true },
});
