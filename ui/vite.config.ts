import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd());

  // Dans un Codespace, « localhost:3000 » n'existe pas pour le navigateur : on pointe le lien Grafana
  // vers l'adresse transférée du port 3000, sauf si VITE_GRAFANA_URL est déjà fixée dans .env.local.
  const { CODESPACE_NAME, GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN } = process.env;
  if (!env.VITE_GRAFANA_URL && CODESPACE_NAME && GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN) {
    process.env.VITE_GRAFANA_URL = `https://${CODESPACE_NAME}-3000.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN}`;
  }

  return {
    plugins: [react(), tailwindcss()],
    server: {
      // En dev, le navigateur appelle /v1/... sur Vite, qui relaie à la gateway : même origine, pas de CORS.
      proxy: {
        '/v1': { target: env.VITE_GATEWAY_URL || 'http://localhost:8000', changeOrigin: true },
      },
    },
  };
});