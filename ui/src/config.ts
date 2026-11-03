const env = import.meta.env;

export const config = {
  apiBaseUrl: (env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:8080',
  cognitoDomain: env.VITE_COGNITO_DOMAIN as string | undefined,
  cognitoClientId: env.VITE_COGNITO_CLIENT_ID as string | undefined,
  cognitoScopes: (env.VITE_COGNITO_SCOPES as string | undefined) ?? 'openid email',
  // Both must exactly match URLs registered on the Cognito app client
  // (callback http://localhost:3000/callback, sign-out http://localhost:3000).
  redirectUri: (env.VITE_REDIRECT_URI as string | undefined) ?? `${window.location.origin}${import.meta.env.BASE_URL}callback`,
  logoutUri: (env.VITE_LOGOUT_URI as string | undefined) ?? `${window.location.origin}${import.meta.env.BASE_URL}`.replace(/\/$/, ''),
};

export const liveLoginConfigured = Boolean(config.cognitoDomain && config.cognitoClientId);
