import { config } from './config';

// OAuth2 Authorization Code + PKCE against the Cognito hosted UI. The
// `wallet-ui` app client has no secret, so PKCE is what proves this browser
// started the login it is finishing.

const TOKEN_KEY = 'wallet.tokens';
const VERIFIER_KEY = 'wallet.pkce';

export interface Session {
  accessToken: string;
  expiresAt: number;
  username: string;
  scopes: string[];
}

function base64Url(bytes: Uint8Array): string {
  let s = '';
  bytes.forEach((b) => (s += String.fromCharCode(b)));
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function decodeJwt(token: string): Record<string, unknown> {
  const part = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  return JSON.parse(atob(part.padEnd(part.length + ((4 - (part.length % 4)) % 4), '=')));
}

function toSession(accessToken: string, idToken?: string): Session {
  const claims = decodeJwt(accessToken);
  const idClaims = idToken ? decodeJwt(idToken) : {};
  return {
    accessToken,
    expiresAt: Number(claims.exp) * 1000,
    username: String(idClaims.email ?? claims.username ?? claims.sub ?? 'user'),
    scopes: String(claims.scope ?? '').split(' ').filter(Boolean),
  };
}

export async function startLogin(): Promise<void> {
  const verifier = base64Url(crypto.getRandomValues(new Uint8Array(32)));
  const challenge = base64Url(
    new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))),
  );
  sessionStorage.setItem(VERIFIER_KEY, verifier);
  const params = new URLSearchParams({
    response_type: 'code',
    client_id: config.cognitoClientId!,
    redirect_uri: config.redirectUri,
    scope: config.cognitoScopes,
    code_challenge_method: 'S256',
    code_challenge: challenge,
  });
  window.location.assign(`https://${config.cognitoDomain}/oauth2/authorize?${params}`);
}

/** Finishes a login if the URL carries ?code=, otherwise restores a stored session. */
export async function resumeSession(): Promise<Session | null> {
  const code = new URLSearchParams(window.location.search).get('code');
  const verifier = sessionStorage.getItem(VERIFIER_KEY);
  if (code && verifier) {
    sessionStorage.removeItem(VERIFIER_KEY);
    window.history.replaceState(null, '', window.location.pathname);
    const res = await fetch(`https://${config.cognitoDomain}/oauth2/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        client_id: config.cognitoClientId!,
        code,
        redirect_uri: config.redirectUri,
        code_verifier: verifier,
      }),
    });
    if (!res.ok) throw new Error(`Sign-in failed (${res.status}): ${await res.text()}`);
    const body = await res.json();
    const session = toSession(body.access_token, body.id_token);
    sessionStorage.setItem(TOKEN_KEY, JSON.stringify(session));
    return session;
  }
  const stored = sessionStorage.getItem(TOKEN_KEY);
  if (!stored) return null;
  const session = JSON.parse(stored) as Session;
  return session.expiresAt > Date.now() + 30_000 ? session : null;
}

export function logout(): void {
  sessionStorage.removeItem(TOKEN_KEY);
  const params = new URLSearchParams({ client_id: config.cognitoClientId!, logout_uri: config.redirectUri });
  window.location.assign(`https://${config.cognitoDomain}/logout?${params}`);
}
