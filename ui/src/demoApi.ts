import type { Account, ChainResult, Entry, Leg, Projection, Transaction, TransferResult, WalletApi } from './api';
import { ApiError } from './api';

// In-browser stand-in for the backend so the UI can be tried without AWS.
// It mirrors the server's rules (balanced legs, no overdraft, idempotency
// replay, SHA-256 hash chain, a projection that lags the ledger) closely
// enough to demonstrate them. Data lives only in this tab.

const GENESIS = '0'.repeat(64);
const PROJECTION_LAG_MS = 1500;

async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function hashInput(prev: string, txId: number, ref: string, dir: string, amount: number, createdAt: string) {
  return `${prev}|${txId}|${ref}|${dir}|${amount.toFixed(2)}|${createdAt}`;
}

const round2 = (n: number) => Math.round(n * 100) / 100;

interface StoredEntry extends Entry {
  accountRef: string;
}

export class DemoWalletApi implements WalletApi {
  private accounts: Account[] = [];
  private entriesList: StoredEntry[] = [];
  private txs: (Transaction & { result: TransferResult })[] = [];
  private projections = new Map<string, Projection>();
  private lastHash = GENESIS;
  private ready: Promise<void>;

  constructor() {
    this.ready = this.seed();
  }

  private async seed() {
    this.addAccount('SYSTEM_TREASURY', 999_999_999.99);
    for (const ref of ['alice', 'bob', 'carol']) this.addAccount(ref, 0);
    await this.post('Opening balance', [
      { accountRef: 'SYSTEM_TREASURY', direction: 'DEBIT', amount: 2500 },
      { accountRef: 'alice', direction: 'CREDIT', amount: 2500 },
    ], 'seed-1', 0);
    await this.post('Opening balance', [
      { accountRef: 'SYSTEM_TREASURY', direction: 'DEBIT', amount: 800 },
      { accountRef: 'bob', direction: 'CREDIT', amount: 800 },
    ], 'seed-2', 0);
    await this.post('Dinner split', [
      { accountRef: 'alice', direction: 'DEBIT', amount: 45.5 },
      { accountRef: 'bob', direction: 'CREDIT', amount: 45.5 },
    ], 'seed-3', 0);
  }

  private addAccount(accountRef: string, balance: number): Account {
    const account = { id: this.accounts.length + 1, accountRef, currency: 'USD', balance, createdAt: new Date().toISOString() };
    this.accounts.push(account);
    return account;
  }

  private async post(description: string, legs: Leg[], key: string, lagMs = PROJECTION_LAG_MS): Promise<TransferResult> {
    const existing = this.txs.find((t) => t.idempotencyKey === key);
    if (existing) return { ...existing.result, replayed: true };

    if (legs.length < 2) throw new ApiError(400, 'A transaction needs at least two legs');
    const debits = round2(legs.filter((l) => l.direction === 'DEBIT').reduce((s, l) => s + l.amount, 0));
    const credits = round2(legs.filter((l) => l.direction === 'CREDIT').reduce((s, l) => s + l.amount, 0));
    if (debits !== credits) throw new ApiError(422, `Transaction is not balanced: debits ${debits} != credits ${credits}`);
    for (const leg of legs) {
      if (!(leg.amount > 0)) throw new ApiError(400, 'amount must be positive');
      const account = this.accounts.find((a) => a.accountRef === leg.accountRef);
      if (!account) throw new ApiError(404, `One or more accounts do not exist: ${leg.accountRef}`);
      if (leg.direction === 'DEBIT' && round2(account.balance - leg.amount) < 0) {
        throw new ApiError(422, `Insufficient funds in ${leg.accountRef}`);
      }
    }

    const txId = this.txs.length + 1;
    const createdAt = new Date().toISOString().replace('Z', '');
    const result: TransferResult = { transactionId: txId, idempotencyKey: key, description, createdAt, entries: [], replayed: false };
    for (const leg of legs) {
      const account = this.accounts.find((a) => a.accountRef === leg.accountRef)!;
      account.balance = round2(account.balance + (leg.direction === 'CREDIT' ? leg.amount : -leg.amount));
      const entryHash = await sha256(hashInput(this.lastHash, txId, leg.accountRef, leg.direction, leg.amount, createdAt));
      const entry: StoredEntry = {
        id: this.entriesList.length + 1, transactionId: txId, accountRef: leg.accountRef, direction: leg.direction,
        amount: leg.amount, createdAt, prevHash: this.lastHash, entryHash,
      };
      this.entriesList.push(entry);
      this.lastHash = entryHash;
      result.entries.push({ entryId: entry.id, accountRef: leg.accountRef, direction: leg.direction, amount: leg.amount, entryHash });
    }
    this.txs.push({ id: txId, idempotencyKey: key, description, createdAt, result });

    // The real projection is fed by outbox -> Kafka -> consumer, so it trails the ledger.
    const snapshot = legs.map((l) => l.accountRef);
    setTimeout(() => {
      for (const ref of new Set(snapshot)) {
        const account = this.accounts.find((a) => a.accountRef === ref)!;
        this.projections.set(ref, { accountRef: ref, balance: account.balance, lastTransactionId: String(txId), updatedAt: new Date().toISOString() });
      }
    }, lagMs);
    return result;
  }

  /** Demo only: edit a stored amount the way a direct database change would, to show verify-chain catching it. */
  tamper(): number | null {
    const target = this.entriesList.find((e) => e.accountRef !== 'SYSTEM_TREASURY');
    if (!target) return null;
    target.amount = round2(target.amount + 1000);
    return target.id;
  }

  async listAccounts() {
    await this.ready;
    return this.accounts.map((a) => ({ ...a }));
  }
  async createAccount(accountRef: string, currency: string) {
    await this.ready;
    if (!accountRef.trim()) throw new ApiError(400, 'accountRef is required');
    if (this.accounts.some((a) => a.accountRef === accountRef)) throw new ApiError(409, `Account already exists: ${accountRef}`);
    const account = this.addAccount(accountRef, 0);
    account.currency = currency || 'USD';
    return { ...account };
  }
  async entries(accountRef: string) {
    await this.ready;
    return this.entriesList.filter((e) => e.accountRef === accountRef).map(({ accountRef: _ref, ...e }) => e);
  }
  async projection(accountRef: string) {
    await this.ready;
    return this.projections.get(accountRef) ?? null;
  }
  async transfer(description: string, legs: Leg[], idempotencyKey: string) {
    await this.ready;
    return this.post(description, legs, idempotencyKey);
  }
  async mint(accountRef: string, amount: number, description: string, idempotencyKey: string) {
    await this.ready;
    return this.post(description ? `Admin mint: ${description}` : 'Admin mint', [
      { accountRef: 'SYSTEM_TREASURY', direction: 'DEBIT', amount },
      { accountRef, direction: 'CREDIT', amount },
    ], idempotencyKey);
  }
  async verifyChain(): Promise<ChainResult> {
    await this.ready;
    let prev = GENESIS;
    for (const e of this.entriesList) {
      if (e.prevHash !== prev) {
        return { valid: false, entriesChecked: this.entriesList.length, firstBrokenEntryId: e.id, detail: `prev_hash mismatch at entry ${e.id}` };
      }
      const recomputed = await sha256(hashInput(e.prevHash, e.transactionId, e.accountRef, e.direction, e.amount, e.createdAt));
      if (recomputed !== e.entryHash) {
        return {
          valid: false, entriesChecked: this.entriesList.length, firstBrokenEntryId: e.id,
          detail: `entry_hash mismatch at entry ${e.id} - row data does not match its recorded hash`,
        };
      }
      prev = e.entryHash;
    }
    return { valid: true, entriesChecked: this.entriesList.length, firstBrokenEntryId: null, detail: 'chain intact' };
  }
  async transactions() {
    await this.ready;
    return this.txs.map(({ result: _r, ...t }) => t);
  }
}
