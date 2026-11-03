import { config } from './config';

export type Direction = 'DEBIT' | 'CREDIT';

export interface Account {
  id: number;
  accountRef: string;
  currency: string;
  balance: number;
  createdAt: string;
}

export interface Entry {
  id: number;
  transactionId: number;
  direction: Direction;
  amount: number;
  createdAt: string;
  prevHash: string;
  entryHash: string;
}

export interface Leg {
  accountRef: string;
  direction: Direction;
  amount: number;
}

export interface TransferResult {
  transactionId: number;
  idempotencyKey: string;
  description: string | null;
  createdAt: string;
  entries: { entryId: number; accountRef: string; direction: Direction; amount: number; entryHash: string }[];
  /** true when the server returned an earlier result for a repeated idempotency key */
  replayed: boolean;
}

export interface Projection {
  accountRef: string;
  balance: number;
  lastTransactionId: string;
  updatedAt: string;
}

export interface ChainResult {
  valid: boolean;
  entriesChecked: number;
  firstBrokenEntryId: number | null;
  detail: string;
}

export interface Transaction {
  id: number;
  idempotencyKey: string;
  description: string | null;
  createdAt: string;
}

export interface WalletApi {
  listAccounts(): Promise<Account[]>;
  createAccount(accountRef: string, currency: string): Promise<Account>;
  entries(accountRef: string): Promise<Entry[]>;
  /** null while the Kafka-fed read model has not caught up yet */
  projection(accountRef: string): Promise<Projection | null>;
  transfer(description: string, legs: Leg[], idempotencyKey: string): Promise<TransferResult>;
  mint(accountRef: string, amount: number, description: string, idempotencyKey: string): Promise<TransferResult>;
  verifyChain(): Promise<ChainResult>;
  transactions(): Promise<Transaction[]>;
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function errorMessage(res: Response): Promise<string> {
  const text = await res.text();
  try {
    const body = JSON.parse(text);
    return body.message ?? body.detail ?? body.error ?? text;
  } catch {
    return text || res.statusText;
  }
}

/** Talks to the Spring Boot service with the signed-in user's Cognito access token. */
export class HttpWalletApi implements WalletApi {
  constructor(private token: string) {}

  private async call<T>(path: string, init: RequestInit = {}, idempotencyKey?: string): Promise<{ status: number; body: T }> {
    const headers: Record<string, string> = { Authorization: `Bearer ${this.token}` };
    if (init.body) headers['Content-Type'] = 'application/json';
    if (idempotencyKey) headers['X-Idempotency-Key'] = idempotencyKey;
    const res = await fetch(`${config.apiBaseUrl}/api/v1${path}`, { ...init, headers });
    if (res.status === 404 && path.endsWith('/projection')) return { status: 404, body: null as T };
    if (!res.ok) throw new ApiError(res.status, await errorMessage(res));
    return { status: res.status, body: (await res.json()) as T };
  }

  async listAccounts() {
    return (await this.call<Account[]>('/accounts')).body;
  }
  async createAccount(accountRef: string, currency: string) {
    return (await this.call<Account>('/accounts', { method: 'POST', body: JSON.stringify({ accountRef, currency }) })).body;
  }
  async entries(accountRef: string) {
    return (await this.call<Entry[]>(`/accounts/${encodeURIComponent(accountRef)}/entries`)).body;
  }
  async projection(accountRef: string) {
    return (await this.call<Projection | null>(`/accounts/${encodeURIComponent(accountRef)}/projection`)).body;
  }
  async transfer(description: string, legs: Leg[], idempotencyKey: string) {
    // 201 = newly posted, 200 = replay of an earlier request with the same key.
    const { status, body } = await this.call<Omit<TransferResult, 'replayed'>>(
      '/transfers', { method: 'POST', body: JSON.stringify({ description, entries: legs }) }, idempotencyKey);
    return { ...body, replayed: status === 200 };
  }
  async mint(accountRef: string, amount: number, description: string, idempotencyKey: string) {
    const { body } = await this.call<Omit<TransferResult, 'replayed'>>(
      `/admin/accounts/${encodeURIComponent(accountRef)}/mint`,
      { method: 'POST', body: JSON.stringify({ amount, description }) }, idempotencyKey);
    return { ...body, replayed: false };
  }
  async verifyChain() {
    return (await this.call<ChainResult>('/audit/verify-chain')).body;
  }
  async transactions() {
    return (await this.call<Transaction[]>('/admin/transactions')).body;
  }
}
