import { useCallback, useEffect, useMemo, useState } from 'react';
import type { Account, ChainResult, Entry, Projection, Transaction, TransferResult, WalletApi } from './api';
import { ApiError, HttpWalletApi } from './api';
import { logout, resumeSession, startLogin, type Session } from './auth';
import { liveLoginConfigured } from './config';
import { DemoWalletApi } from './demoApi';

type Mode = { kind: 'demo'; api: DemoWalletApi } | { kind: 'live'; api: WalletApi; session: Session };
type Tab = 'accounts' | 'transfer' | 'audit' | 'admin';

const money = (n: number, currency = 'USD') =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(n);
const shortHash = (h: string) => `${h.slice(0, 8)}…${h.slice(-6)}`;
const newKey = () => crypto.randomUUID();
const describeError = (e: unknown) => (e instanceof ApiError ? `${e.status}: ${e.message}` : String(e));

export default function App() {
  const [mode, setMode] = useState<Mode | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!liveLoginConfigured) {
      setLoading(false);
      return;
    }
    resumeSession()
      .then((session) => session && setMode({ kind: 'live', api: new HttpWalletApi(session.accessToken), session }))
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="center muted">Loading…</div>;
  if (!mode) return <Landing error={error} onDemo={() => setMode({ kind: 'demo', api: new DemoWalletApi() })} />;
  return <Shell mode={mode} onExit={() => (mode.kind === 'live' ? logout() : setMode(null))} />;
}

function Landing({ error, onDemo }: { error: string | null; onDemo: () => void }) {
  return (
    <div className="landing">
      <div className="landing-card">
        <h1>Wallet Ledger</h1>
        <p className="muted">
          A double-entry ledger with idempotent transfers, a Kafka-fed balance projection and a
          tamper-evident SHA-256 hash chain.
        </p>
        {error && <div className="alert error">{error}</div>}
        <div className="stack">
          <button className="primary" disabled={!liveLoginConfigured} onClick={() => startLogin()}>
            Sign in with AWS Cognito
          </button>
          {!liveLoginConfigured && (
            <p className="hint">Live sign-in needs VITE_COGNITO_DOMAIN and VITE_COGNITO_CLIENT_ID (see ui/.env.example).</p>
          )}
          <button onClick={onDemo}>Try the demo (sample data, runs in your browser)</button>
        </div>
      </div>
    </div>
  );
}

function Shell({ mode, onExit }: { mode: Mode; onExit: () => void }) {
  const isAdmin = mode.kind === 'demo' || mode.session.scopes.includes('wallet-api/admin');
  const [tab, setTab] = useState<Tab>('accounts');
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setAccounts(await mode.api.listAccounts());
      setError(null);
    } catch (e) {
      setError(describeError(e));
    }
  }, [mode.api]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const tabs: [Tab, string][] = [['accounts', 'Accounts'], ['transfer', 'Transfer'], ['audit', 'Audit']];
  if (isAdmin) tabs.push(['admin', 'Admin']);

  return (
    <div className="app">
      {mode.kind === 'demo' && (
        <div className="demo-banner">Demo mode: sample data, stored only in this browser tab. Nothing is sent to a server.</div>
      )}
      <header>
        <div className="brand">Wallet Ledger</div>
        <nav>
          {tabs.map(([key, label]) => (
            <button key={key} className={tab === key ? 'tab active' : 'tab'} onClick={() => setTab(key)}>
              {label}
            </button>
          ))}
        </nav>
        <div className="user">
          <span className="muted">{mode.kind === 'live' ? mode.session.username : 'demo user'}</span>
          <button onClick={onExit}>{mode.kind === 'live' ? 'Sign out' : 'Exit demo'}</button>
        </div>
      </header>
      <main>
        {error && <div className="alert error">{error}</div>}
        {tab === 'accounts' && (
          <AccountsView api={mode.api} accounts={accounts} selected={selected} onSelect={setSelected} onChanged={refresh} />
        )}
        {tab === 'transfer' && <TransferView api={mode.api} accounts={accounts} onDone={refresh} />}
        {tab === 'audit' && <AuditView api={mode.api} demo={mode.kind === 'demo' ? mode.api : null} />}
        {tab === 'admin' && isAdmin && <AdminView api={mode.api} accounts={accounts} onDone={refresh} />}
      </main>
    </div>
  );
}

function AccountsView({ api, accounts, selected, onSelect, onChanged }: {
  api: WalletApi; accounts: Account[]; selected: string | null; onSelect: (ref: string) => void; onChanged: () => void;
}) {
  const [ref, setRef] = useState('');
  const [msg, setMsg] = useState<string | null>(null);
  const visible = accounts.filter((a) => a.accountRef !== 'SYSTEM_TREASURY');

  async function create(e: React.FormEvent) {
    e.preventDefault();
    try {
      await api.createAccount(ref.trim(), 'USD');
      setMsg(`Created ${ref.trim()}`);
      setRef('');
      onChanged();
    } catch (err) {
      setMsg(describeError(err));
    }
  }

  return (
    <div className="grid">
      <section className="card">
        <h2>Accounts</h2>
        <table>
          <thead><tr><th>Account</th><th className="num">Balance</th></tr></thead>
          <tbody>
            {visible.map((a) => (
              <tr key={a.accountRef} className={selected === a.accountRef ? 'selected clickable' : 'clickable'} onClick={() => onSelect(a.accountRef)}>
                <td>{a.accountRef}</td>
                <td className="num">{money(a.balance, a.currency)}</td>
              </tr>
            ))}
            {visible.length === 0 && <tr><td colSpan={2} className="muted">No accounts yet.</td></tr>}
          </tbody>
        </table>
        <form className="inline" onSubmit={create}>
          <input placeholder="new account name" value={ref} onChange={(e) => setRef(e.target.value)} required />
          <button type="submit">Create</button>
        </form>
        {msg && <p className="hint">{msg}</p>}
      </section>
      {selected ? <AccountDetail key={selected} api={api} account={accounts.find((a) => a.accountRef === selected)} accountRef={selected} />
        : <section className="card muted">Select an account to see its ledger history.</section>}
    </div>
  );
}

function AccountDetail({ api, account, accountRef }: { api: WalletApi; account?: Account; accountRef: string }) {
  const [entries, setEntries] = useState<Entry[]>([]);
  const [projection, setProjection] = useState<Projection | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const [e, p] = await Promise.all([api.entries(accountRef), api.projection(accountRef)]);
        if (!cancelled) {
          setEntries(e);
          setProjection(p);
        }
      } catch (err) {
        if (!cancelled) setError(describeError(err));
      }
    };
    load();
    // Poll so the projection visibly catches up with the ledger.
    const timer = setInterval(load, 2000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [api, accountRef, account?.balance]);

  const inSync = projection && account && projection.balance === account.balance;
  return (
    <section className="card">
      <h2>{accountRef}</h2>
      {error && <div className="alert error">{error}</div>}
      <div className="stats">
        <div><div className="label">Ledger balance</div><div className="big">{account ? money(account.balance, account.currency) : '—'}</div></div>
        <div>
          <div className="label">Projection (via Kafka)</div>
          <div className="big">{projection ? money(projection.balance) : '—'}</div>
          <div className={inSync ? 'pill ok' : 'pill warn'}>{inSync ? 'in sync' : 'catching up'}</div>
        </div>
      </div>
      <table>
        <thead><tr><th>Txn</th><th>Direction</th><th className="num">Amount</th><th>Entry hash</th></tr></thead>
        <tbody>
          {[...entries].reverse().map((e) => (
            <tr key={e.id}>
              <td>#{e.transactionId}</td>
              <td><span className={e.direction === 'CREDIT' ? 'pill ok' : 'pill'}>{e.direction}</span></td>
              <td className="num">{money(e.amount)}</td>
              <td className="mono" title={`prev ${e.prevHash}\nthis ${e.entryHash}`}>{shortHash(e.entryHash)}</td>
            </tr>
          ))}
          {entries.length === 0 && <tr><td colSpan={4} className="muted">No entries yet.</td></tr>}
        </tbody>
      </table>
    </section>
  );
}

function TransferView({ api, accounts, onDone }: { api: WalletApi; accounts: Account[]; onDone: () => void }) {
  const refs = useMemo(() => accounts.map((a) => a.accountRef).filter((r) => r !== 'SYSTEM_TREASURY'), [accounts]);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [key, setKey] = useState(newKey);
  const [result, setResult] = useState<TransferResult | null>(null);
  const [retryNote, setRetryNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!from && refs[0]) setFrom(refs[0]);
    if (!to && refs[1]) setTo(refs[1]);
  }, [refs, from, to]);

  async function send(sameKey: boolean) {
    setBusy(true);
    setError(null);
    if (!sameKey) setRetryNote(null);
    const value = Number(amount);
    try {
      const res = await api.transfer(description, [
        { accountRef: from, direction: 'DEBIT', amount: value },
        { accountRef: to, direction: 'CREDIT', amount: value },
      ], key);
      if (sameKey) {
        setRetryNote(res.replayed
          ? `Server returned the original transaction #${res.transactionId}. No second transfer was made.`
          : `Posted as transaction #${res.transactionId}.`);
      }
      setResult(res);
      onDone();
    } catch (e) {
      if (sameKey && e instanceof ApiError && e.status === 409) {
        setRetryNote('Server rejected the repeat as a duplicate (409). No second transfer was made.');
      } else {
        setError(describeError(e));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid">
      <section className="card">
        <h2>New transfer</h2>
        <form className="stack" onSubmit={(e) => { e.preventDefault(); send(false); }}>
          <label>From<select value={from} onChange={(e) => setFrom(e.target.value)}>{refs.map((r) => <option key={r}>{r}</option>)}</select></label>
          <label>To<select value={to} onChange={(e) => setTo(e.target.value)}>{refs.map((r) => <option key={r}>{r}</option>)}</select></label>
          <label>Amount (USD)<input type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} required /></label>
          <label>Description<input value={description} onChange={(e) => setDescription(e.target.value)} placeholder="optional" /></label>
          <label>Idempotency key
            <div className="inline">
              <input className="mono" value={key} readOnly />
              <button type="button" onClick={() => { setKey(newKey()); setResult(null); setRetryNote(null); }}>New</button>
            </div>
          </label>
          <button className="primary" type="submit" disabled={busy || !from || !to || from === to}>Send transfer</button>
          {from === to && from && <p className="hint">Pick two different accounts.</p>}
        </form>
        {error && <div className="alert error">{error}</div>}
      </section>
      <section className="card">
        <h2>Result</h2>
        {!result && <p className="muted">Each transfer posts one debit and one credit that must balance. The idempotency key makes retries safe.</p>}
        {result && (
          <>
            <p>Transaction <strong>#{result.transactionId}</strong>{result.replayed ? ' (replayed)' : ''}</p>
            <table>
              <thead><tr><th>Account</th><th>Direction</th><th className="num">Amount</th><th>Hash</th></tr></thead>
              <tbody>
                {result.entries.map((e) => (
                  <tr key={e.entryId}><td>{e.accountRef}</td><td>{e.direction}</td><td className="num">{money(e.amount)}</td><td className="mono">{shortHash(e.entryHash)}</td></tr>
                ))}
              </tbody>
            </table>
            <div className="callout">
              <p><strong>Simulate a network retry.</strong> Send the same request again with the same idempotency key, as a client would after a timeout.</p>
              <button disabled={busy} onClick={() => send(true)}>Retry with the same key</button>
              {retryNote && <p className="ok-text">{retryNote}</p>}
            </div>
          </>
        )}
      </section>
    </div>
  );
}

function AuditView({ api, demo }: { api: WalletApi; demo: DemoWalletApi | null }) {
  const [result, setResult] = useState<ChainResult | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function verify() {
    setError(null);
    try {
      setResult(await api.verifyChain());
    } catch (e) {
      setError(describeError(e));
    }
  }

  return (
    <section className="card narrow">
      <h2>Audit: verify the hash chain</h2>
      <p className="muted">
        Every ledger entry stores a SHA-256 hash of its own data plus the previous entry's hash. Verification recomputes
        the whole chain, so any edit made outside the application shows up as a break.
      </p>
      <div className="inline">
        <button className="primary" onClick={verify}>Verify chain</button>
        {demo && (
          <button onClick={() => {
            const id = demo.tamper();
            setResult(null);
            setNote(id ? `Changed the amount on entry ${id} directly, as a database edit would. Now verify again.` : null);
          }}>Simulate tampering (demo)</button>
        )}
      </div>
      {note && <p className="hint">{note}</p>}
      {error && <div className="alert error">{error}</div>}
      {result && (
        <div className={result.valid ? 'alert ok' : 'alert error'}>
          <strong>{result.valid ? 'Chain intact' : 'Chain broken'}</strong>: {result.entriesChecked} entries checked.
          {!result.valid && <> First broken entry: #{result.firstBrokenEntryId}. {result.detail}</>}
        </div>
      )}
    </section>
  );
}

function AdminView({ api, accounts, onDone }: { api: WalletApi; accounts: Account[]; onDone: () => void }) {
  const refs = accounts.map((a) => a.accountRef).filter((r) => r !== 'SYSTEM_TREASURY');
  const [target, setTarget] = useState('');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [msg, setMsg] = useState<string | null>(null);
  const [txs, setTxs] = useState<Transaction[]>([]);

  const loadTxs = useCallback(async () => {
    try {
      setTxs(await api.transactions());
    } catch (e) {
      setMsg(describeError(e));
    }
  }, [api]);

  useEffect(() => {
    loadTxs();
  }, [loadTxs]);
  useEffect(() => {
    if (!target && refs[0]) setTarget(refs[0]);
  }, [refs, target]);

  async function mint(e: React.FormEvent) {
    e.preventDefault();
    try {
      const res = await api.mint(target, Number(amount), description, newKey());
      setMsg(`Funded ${target} with ${money(Number(amount))} (transaction #${res.transactionId}), debited from SYSTEM_TREASURY.`);
      setAmount('');
      onDone();
      loadTxs();
    } catch (err) {
      setMsg(describeError(err));
    }
  }

  return (
    <div className="grid">
      <section className="card">
        <h2>Fund an account</h2>
        <p className="muted">Admin top-ups go through the same double-entry path, debiting the treasury account.</p>
        <form className="stack" onSubmit={mint}>
          <label>Account<select value={target} onChange={(e) => setTarget(e.target.value)}>{refs.map((r) => <option key={r}>{r}</option>)}</select></label>
          <label>Amount (USD)<input type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} required /></label>
          <label>Description<input value={description} onChange={(e) => setDescription(e.target.value)} placeholder="optional" /></label>
          <button className="primary" type="submit">Fund account</button>
        </form>
        {msg && <p className="hint">{msg}</p>}
      </section>
      <section className="card">
        <h2>All transactions</h2>
        <table>
          <thead><tr><th>#</th><th>Description</th><th>Idempotency key</th></tr></thead>
          <tbody>
            {[...txs].reverse().map((t) => (
              <tr key={t.id}><td>{t.id}</td><td>{t.description ?? '—'}</td><td className="mono">{t.idempotencyKey.slice(0, 13)}</td></tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}
