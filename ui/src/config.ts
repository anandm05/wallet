const env = import.meta.env;

export const config = {
  apiBaseUrl: (env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:8080',
  cognitoDomain: env.VITE_COGNITO_DOMAIN as string | undefined,
  cognitoClientId: env.VITE_COGNITO_CLIENT_ID as string | undefined,
  cognitoScopes: (env.VITE_COGNITO_SCOPES as string | undefined) ?? 'openid email',
  // Must exactly match a callback URL registered on the Cognito app client.
  redirectUri: window.location.origin + import.meta.env.BASE_URL,
};

export const liveLoginConfigured = Boolean(config.cognitoDomain && config.cognitoClientId);
