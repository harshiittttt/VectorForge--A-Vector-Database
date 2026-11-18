import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  Activity, ArrowDownToLine, ArrowUpRight, Boxes, Check, ChevronRight,
  CircleHelp, Clock3, Cpu, Database, FileText, Gauge, HardDrive,
  LayoutDashboard, LoaderCircle, Network, Plus, RefreshCw, Search,
  ShieldCheck, Trash2, Upload, Workflow, X, Zap,
} from 'lucide-react';
import './style.css';

const API = '/api';
const DB_NAME = 'vectorforge-console';
const STORE_DOCS = 'documents';
const STORE_VECTORS = 'vectors';
const NAV = [
  { label: 'Overview', icon: LayoutDashboard },
  { label: 'Search', icon: Search },
  { label: 'Documents', icon: FileText },
  { label: 'Benchmark', icon: Gauge },
];

function openDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_DOCS)) db.createObjectStore(STORE_DOCS, { keyPath: 'id' });
      if (!db.objectStoreNames.contains(STORE_VECTORS)) db.createObjectStore(STORE_VECTORS, { keyPath: 'id' });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function storeRequest(storeName, mode, action) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(storeName, mode);
    const store = tx.objectStore(storeName);
    const request = action(store);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    tx.oncomplete = () => db.close();
    tx.onerror = () => reject(tx.error);
  });
}

const listDocuments = () => storeRequest(STORE_DOCS, 'readonly', store => store.getAll());
const getVector = id => storeRequest(STORE_VECTORS, 'readonly', store => store.get(id));
const saveDocument = doc => storeRequest(STORE_DOCS, 'readwrite', store => store.put(doc));
const saveVector = item => storeRequest(STORE_VECTORS, 'readwrite', store => store.put(item));

async function removeLocalDocument(id) {
  const db = await openDb();
  await new Promise((resolve, reject) => {
    const tx = db.transaction([STORE_DOCS, STORE_VECTORS], 'readwrite');
    tx.objectStore(STORE_DOCS).delete(id);
    tx.objectStore(STORE_VECTORS).delete(id);
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
  db.close();
}

async function clearLocalStores() {
  const db = await openDb();
  await new Promise((resolve, reject) => {
    const tx = db.transaction([STORE_DOCS, STORE_VECTORS], 'readwrite');
    tx.objectStore(STORE_DOCS).clear();
    tx.objectStore(STORE_VECTORS).clear();
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
  db.close();
}

async function cacheLegacyDocuments(onProgress) {
  let clusterIsEmpty = false;
  try {
    const response = await fetch('/api/v1/cluster/nodes');
    if (response.ok) {
      const stats = await response.json();
      const activeVectors = stats.nodes.reduce((sum, node) => sum + (node.vector_count || 0), 0);
      clusterIsEmpty = stats.node_count > 0 && activeVectors === 0;
    }
  } catch {
    // Keep the local cache available while the Java cluster is offline.
  }

  const existingDocuments = await listDocuments();
  if (clusterIsEmpty) {
    if (existingDocuments.length) await clearLocalStores();
    localStorage.setItem('vectorforge-legacy-import-v1', 'yes');
    return 0;
  }
  if (localStorage.getItem('vectorforge-legacy-import-v1') && existingDocuments.length > 0) return 0;

  const response = await fetch('/legacy-documents.json');
  if (!response.ok) return 0;
  const legacy = await response.json();
  const db = await openDb();
  await new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_DOCS, 'readwrite');
    const store = tx.objectStore(STORE_DOCS);
    legacy.forEach((item, index) => {
      store.put({ id: item.id, text: item.text || '', createdAt: Date.now() - index });
      if (index % 1000 === 0) onProgress(index, legacy.length);
    });
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
  db.close();
  localStorage.setItem('vectorforge-legacy-import-v1', 'yes');
  return legacy.length;
}

async function request(path, options = {}) {
  const response = await fetch(`${API}${path}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...options.headers },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error || `Request failed (${response.status})`);
  return body;
}

const post = (path, payload) => request(path, { method: 'POST', body: JSON.stringify(payload) });
const formatNumber = value => new Intl.NumberFormat('en-US').format(value || 0);
const formatBytes = value => {
  if (!value) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const unit = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${(value / (1024 ** unit)).toFixed(unit ? 1 : 0)} ${units[unit]}`;
};
const shortId = value => value?.length > 18 ? `${value.slice(0, 8)}…${value.slice(-5)}` : value;

function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < text.length; i += 1) {
    const char = text[i];
    if (char === '"' && quoted && text[i + 1] === '"') { cell += '"'; i += 1; }
    else if (char === '"') quoted = !quoted;
    else if (char === ',' && !quoted) { row.push(cell); cell = ''; }
    else if ((char === '\n' || char === '\r') && !quoted) {
      if (char === '\r' && text[i + 1] === '\n') i += 1;
      row.push(cell); rows.push(row); row = []; cell = '';
    } else cell += char;
  }
  if (cell || row.length) { row.push(cell); rows.push(row); }
  if (rows.length < 2) return [];
  const headers = rows.shift().map(value => value.trim().toLowerCase());
  const textIndex = headers.indexOf('text');
  const idIndex = headers.indexOf('id');
  if (textIndex < 0) throw new Error('CSV needs a column named "text".');
  return rows.map(values => ({ id: values[idIndex]?.trim() || '', text: values[textIndex]?.trim() || '' })).filter(item => item.text);
}

function useEmbeddingWorker() {
  const workerRef = useRef(null);
  const pending = useRef(new Map());
  const [modelState, setModelState] = useState('idle');
  const [modelProgress, setModelProgress] = useState(0);

  useEffect(() => {
    const worker = new Worker(new URL('./embedding.worker.js', import.meta.url), { type: 'module' });
    workerRef.current = worker;
    worker.onmessage = event => {
      const message = event.data;
      if (message.type === 'model-progress') {
        setModelState('loading');
        const value = message.progress?.progress;
        if (Number.isFinite(value)) setModelProgress(Math.round(value));
        return;
      }
      const job = pending.current.get(message.jobId);
      if (!job) return;
      if (message.type === 'embedded') {
        setModelState('ready');
        setModelProgress(100);
        pending.current.delete(message.jobId);
        job.resolve(message.vectors);
      } else if (message.type === 'error') {
        setModelState('error');
        pending.current.delete(message.jobId);
        job.reject(new Error(message.message));
      }
    };
    worker.onerror = error => {
      pending.current.forEach(job => job.reject(error));
      pending.current.clear();
      setModelState('error');
    };
    return () => worker.terminate();
  }, []);

  const embed = texts => new Promise((resolve, reject) => {
    const jobId = crypto.randomUUID();
    pending.current.set(jobId, { resolve, reject });
    setModelState('loading');
    workerRef.current.postMessage({ type: 'embed', jobId, texts });
  });
  return { embed, modelState, modelProgress };
}

function RecallChart({ rows }) {
  if (!rows?.length) return <div className="chart-empty">Run a benchmark to plot the accuracy / latency frontier.</div>;
  const width = 720;
  const height = 265;
  const padding = { left: 54, right: 24, top: 22, bottom: 44 };
  const maxLatency = Math.max(...rows.map(item => item.latency), rows[0].baseline || 1) * 1.18;
  const x = value => padding.left + value * (width - padding.left - padding.right);
  const y = value => height - padding.bottom - (value / maxLatency) * (height - padding.top - padding.bottom);
  const points = rows.map(item => `${x(item.recall)},${y(item.latency)}`).join(' ');
  return (
    <svg className="recall-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Recall versus end-to-end latency">
      {[0, 0.25, 0.5, 0.75, 1].map(tick => (
        <g key={tick}>
          <line x1={padding.left} x2={width - padding.right} y1={y(maxLatency * tick)} y2={y(maxLatency * tick)} className="chart-grid" />
          <text x={padding.left - 10} y={y(maxLatency * tick) + 4} textAnchor="end" className="chart-label">{(maxLatency * tick).toFixed(0)} ms</text>
        </g>
      ))}
      <line x1={x(0)} x2={x(1)} y1={padding.top} y2={padding.top} className="chart-axis" />
      <line x1={padding.left} x2={padding.left} y1={padding.top} y2={height - padding.bottom} className="chart-axis" />
      <line x1={x(0)} x2={x(1)} y1={y(rows[0].baseline)} y2={y(rows[0].baseline)} className="baseline-line" />
      <text x={width - padding.right} y={y(rows[0].baseline) - 7} textAnchor="end" className="chart-baseline-label">Exact / Java {rows[0].baseline.toFixed(1)} ms</text>
      <polyline points={points} className="recall-line" />
      {rows.map(item => <g key={item.ef}><circle cx={x(item.recall)} cy={y(item.latency)} r="5" className="recall-dot" /><text x={x(item.recall) + 8} y={y(item.latency) - 8} className="chart-point-label">ef {item.ef}</text></g>)}
      <text x={(padding.left + width - padding.right) / 2} y={height - 8} textAnchor="middle" className="chart-axis-label">Recall@K</text>
      <text transform={`translate(15 ${(padding.top + height - padding.bottom) / 2}) rotate(-90)`} textAnchor="middle" className="chart-axis-label">Average latency</text>
    </svg>
  );
}

function App() {
  const { embed, modelState, modelProgress } = useEmbeddingWorker();
  const [page, setPage] = useState('Overview');
  const [docs, setDocs] = useState([]);
  const [stats, setStats] = useState(null);
  const [boot, setBoot] = useState('Loading workspace');
  const [notice, setNotice] = useState(null);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState(null);
  const [docText, setDocText] = useState('');
  const [docId, setDocId] = useState('');
  const [bulkItems, setBulkItems] = useState([]);
  const [searchText, setSearchText] = useState('');
  const [searchResults, setSearchResults] = useState([]);
  const [searchPerformed, setSearchPerformed] = useState(false);
  const [searchBusy, setSearchBusy] = useState(false);
  const [topK, setTopK] = useState(10);
  const [efSearch, setEfSearch] = useState(100);
  const [filter, setFilter] = useState('');
  const [benchmark, setBenchmark] = useState(null);
  const [benchmarkConfig, setBenchmarkConfig] = useState({ k: 5, queries: 20 });
  const [uploadMode, setUploadMode] = useState('file');
  const [pastedText, setPastedText] = useState('');
  const fileInput = useRef(null);

  const notify = (kind, message) => {
    setNotice({ kind, message });
    window.setTimeout(() => setNotice(null), 5000);
  };
  const refreshStats = async () => {
    try { setStats(await request('/v1/cluster/nodes')); }
    catch { setStats(null); }
  };
  const refreshDocs = async () => setDocs(await listDocuments());

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const count = await cacheLegacyDocuments(() => {});
        await refreshDocs();
        if (alive) {
          setBoot('ready');
          if (count) notify('success', `Restored ${formatNumber(count)} existing document texts into this browser.`);
        }
      } catch (error) {
        if (alive) { setBoot('ready'); notify('error', `Local document cache: ${error.message}`); }
      }
      refreshStats();
    })();
    const timer = window.setInterval(refreshStats, 4000);
    return () => { alive = false; window.clearInterval(timer); };
  }, []);

  const healthyNodes = stats?.nodes?.filter(node => node.status === 'healthy').length || 0;
  const totalReplicaVectors = stats?.nodes?.reduce((sum, node) => sum + (node.vector_count || 0), 0) || 0;
  const totalMemtable = stats?.nodes?.reduce((sum, node) => sum + (node.memtable_bytes || 0), 0) || 0;
  const filteredDocs = useMemo(() => {
    const value = filter.trim().toLowerCase();
    const source = value ? docs.filter(item => item.id.toLowerCase().includes(value) || item.text.toLowerCase().includes(value)) : docs;
    return source.slice(0, 250);
  }, [docs, filter]);

  const addVector = async (item, vector) => {
    const id = item.id?.trim() || crypto.randomUUID();
    await post('/upsert', { id, vector });
    await saveDocument({ id, text: item.text, createdAt: Date.now() });
    await saveVector({ id, vector });
    return id;
  };

  const handleSingleInsert = async event => {
    event.preventDefault();
    if (!docText.trim()) return notify('error', 'Add document text before indexing.');
    setBusy(true);
    setProgress({ done: 0, total: 1, label: 'Embedding document' });
    try {
      const [vector] = await embed([docText.trim()]);
      const id = await addVector({ id: docId, text: docText.trim() }, vector);
      await refreshDocs(); await refreshStats();
      setDocText(''); setDocId('');
      notify('success', `Indexed ${shortId(id)} across its replica set.`);
    } catch (error) { notify('error', error.message); }
    finally { setBusy(false); setProgress(null); }
  };

  const handleBulkFile = async file => {
    if (!file) return;
    try {
      const raw = await file.text();
      const items = file.name.toLowerCase().endsWith('.csv')
        ? parseCsv(raw)
        : raw.split(/\r?\n/).map(text => ({ id: '', text: text.trim() })).filter(item => item.text);
      setBulkItems(items);
      notify('success', `Loaded ${formatNumber(items.length)} documents from ${file.name}.`);
    } catch (error) { notify('error', error.message); }
  };

  const handleBulkPaste = () => {
    const items = pastedText.split(/\r?\n/).map(text => ({ id: '', text: text.trim() })).filter(item => item.text);
    setBulkItems(items);
    notify('success', `Prepared ${formatNumber(items.length)} pasted documents.`);
  };

  const runBulkInsert = async () => {
    if (!bulkItems.length) return;
    setBusy(true);
    let completed = 0;
    let failed = 0;
    try {
      const batchSize = 12;
      for (let offset = 0; offset < bulkItems.length; offset += batchSize) {
        const batch = bulkItems.slice(offset, offset + batchSize);
        setProgress({ done: completed, total: bulkItems.length, label: `Embedding ${Math.min(offset + batch.length, bulkItems.length)} of ${formatNumber(bulkItems.length)}` });
        const vectors = await embed(batch.map(item => item.text));
        const outcomes = await Promise.all(batch.map(async (item, index) => {
          try { await addVector(item, vectors[index]); return true; }
          catch { failed += 1; return false; }
        }));
        completed += outcomes.filter(Boolean).length;
        setProgress({ done: Math.min(offset + batch.length, bulkItems.length), total: bulkItems.length, label: `Indexed ${formatNumber(Math.min(offset + batch.length, bulkItems.length))} of ${formatNumber(bulkItems.length)}` });
      }
      await refreshDocs(); await refreshStats();
      notify(failed ? 'warning' : 'success', `Inserted ${formatNumber(completed)} documents${failed ? `; ${formatNumber(failed)} failed` : ''}.`);
      setBulkItems([]); setPastedText('');
    } catch (error) { notify('error', error.message); }
    finally { setBusy(false); setProgress(null); }
  };

  const runSearch = async event => {
    event?.preventDefault();
    if (!searchText.trim()) return;
    setSearchBusy(true); setSearchResults([]);
    try {
      const [vector] = await embed([searchText.trim()]);
      const raw = await post('/query', { vector, top_k: Number(topK), ef_search: Number(efSearch) });
      // Guard: coordinator returns a bare JSON array; if something goes wrong it may be an object
      const results = Array.isArray(raw) ? raw : [];
      const documents = await listDocuments();
      const byId = new Map(documents.map(item => [item.id, item.text]));
      const enriched = results.map(item => ({ ...item, text: byId.get(item.id) || '' }));
      setSearchResults(enriched);
      setSearchPerformed(true);
      if (enriched.length === 0) {
        notify('warning', 'No results returned. Make sure the Java cluster is running and you have indexed documents.');
      }
    } catch (error) { notify('error', `Search failed: ${error.message}`); }
    finally { setSearchBusy(false); }
  };

  const deleteOne = async item => {
    if (!window.confirm(`Delete ${shortId(item.id)} from the Java cluster and this browser?`)) return;
    try {
      await post('/delete', { id: item.id });
      await removeLocalDocument(item.id);
      await refreshDocs(); await refreshStats();
      notify('success', 'Document and replicated vectors deleted.');
    } catch (error) { notify('error', error.message); }
  };

  const clearAll = async () => {
    if (!docs.length && !totalReplicaVectors) {
      notify('success', 'The local library and Java shards are already empty.');
      return;
    }
    const confirmed = window.confirm(
      `Clear ${formatNumber(docs.length)} local document texts and all ${formatNumber(totalReplicaVectors)} vector copies across every Java shard? This cannot be undone.`,
    );
    if (!confirmed) return;

    setBusy(true);
    setProgress({ done: 0, total: 1, label: 'Writing durable clear tombstones to every shard' });
    try {
      const result = await post('/v1/cluster/clear', {});
      await clearLocalStores();
      await refreshDocs();
      await refreshStats();
      setProgress(null);
      notify('success', `Cleared ${formatNumber(result.deleted_vectors)} active vectors across all shards and removed local text.`);
    } catch (error) {
      setProgress(null);
      notify('error', `Cluster clear failed; local documents were preserved. ${error.message}`);
    } finally {
      setBusy(false);
    }
  };

  const runBenchmark = async () => {
    if (docs.length < 5) return notify('error', 'Index at least five documents before benchmarking.');
    setBusy(true); setBenchmark(null);
    const queryCount = Math.min(Number(benchmarkConfig.queries), docs.length);
    const stride = Math.max(1, Math.floor(docs.length / queryCount));
    const samples = docs.filter((_, index) => index % stride === 0).slice(0, queryCount);
    setProgress({ done: 0, total: queryCount, label: 'Embedding held-out queries in your browser' });
    try {
      const vectors = await embed(samples.map(item => item.text));
      const k = Math.min(Number(benchmarkConfig.k), Math.max(1, totalReplicaVectors - 1));
      let start = performance.now();
      const exact = await Promise.all(vectors.map(vector => post('/bruteforce/query', { vector, top_k: k + 1 })));
      const baseline = (performance.now() - start) / queryCount;
      const truth = exact.map((results, index) => new Set(results.filter(item => item.id !== samples[index].id).slice(0, k).map(item => item.id)));
      const rows = [];
      for (const ef of [Math.max(10, k), Math.max(20, k), 40, 80, 150, 300].filter((value, index, array) => array.indexOf(value) === index)) {
        setProgress({ done: rows.length, total: 6, label: `Measuring Java HNSW at ef_search ${ef}` });
        start = performance.now();
        const results = await Promise.all(vectors.map(vector => post('/query', { vector, top_k: k + 1, ef_search: ef })));
        const latency = (performance.now() - start) / queryCount;
        let matches = 0;
        results.forEach((items, index) => {
          const found = new Set(items.filter(item => item.id !== samples[index].id).slice(0, k).map(item => item.id));
          for (const id of found) if (truth[index].has(id)) matches += 1;
        });
        rows.push({ ef, recall: matches / (queryCount * k), latency, baseline, speedup: baseline / latency });
      }
      setBenchmark({ docs: totalReplicaVectors, queries: queryCount, k, baseline, rows, date: new Date() });
      notify('success', 'Java HNSW versus Java brute-force benchmark finished.');
    } catch (error) { notify('error', error.message); }
    finally { setBusy(false); setProgress(null); }
  };

  const downloadCsv = () => {
    const contents = ['id,text', ...docs.map(item => `${JSON.stringify(item.id)},${JSON.stringify(item.text)}`)].join('\n');
    const link = document.createElement('a');
    link.href = URL.createObjectURL(new Blob([contents], { type: 'text/csv' }));
    link.download = 'vectorforge-documents.csv'; link.click(); URL.revokeObjectURL(link.href);
  };

  return (
    <div className="app-shell">
      <aside className="rail">
        <a className="brand" href="#overview" onClick={() => setPage('Overview')}>
          <span className="brand-mark"><Workflow size={19} strokeWidth={2.4} /></span>
          <span><strong>VECTOR<span>FORGE</span></strong><small>VECTOR OPERATIONS</small></span>
        </a>
        <div className="rail-section-label">WORKSPACE</div>
        <nav className="main-nav">
          {NAV.map(item => {
            const Icon = item.icon;
            return <button key={item.label} aria-label={item.label} className={`nav-item ${page === item.label ? 'active' : ''}`} onClick={() => setPage(item.label)}><Icon size={17} /><span>{item.label}</span>{page === item.label && <ChevronRight size={15} className="nav-chevron" />}</button>;
          })}
        </nav>
        <div className="rail-spacer" />
        <div className="rail-cluster">
          <div className="cluster-heading"><span className={`live-dot ${healthyNodes === 3 ? 'online' : ''}`} />CLUSTER STATUS</div>
          <div className="cluster-count">{String(healthyNodes).padStart(2, '0')}<span> / {String(stats?.node_count || 3).padStart(2, '0')} nodes</span></div>
          <div className="cluster-mini-nodes">{(stats?.nodes || [{ id: 'node-1' }, { id: 'node-2' }, { id: 'node-3' }]).map(node => <i key={node.id} className={node.status === 'healthy' ? 'healthy' : ''} title={`${node.id}: ${node.status || 'checking'}`} />)}</div>
          <button className="rail-refresh" onClick={refreshStats}><RefreshCw size={13} /> Refresh cluster</button>
        </div>
        <div className="rail-foot"><span>VECTORFORGE</span><span>BUILD 02.7</span></div>
      </aside>

      <main className="main-area">
        <header className="topbar">
          <div className="breadcrumbs"><span>OPERATIONS</span><i>/</i><strong>{page.toUpperCase()}</strong></div>
          <div className="top-actions">
            <span className="server-chip"><span className={`live-dot ${stats ? 'online' : ''}`} />{stats ? 'Coordinator connected' : 'Coordinator offline'}</span>
            <button className="icon-button" title="Refresh cluster status" onClick={refreshStats}><RefreshCw size={16} /></button>
            <span className="avatar">VF</span>
          </div>
        </header>

        <div className="content-wrap">
          {page === 'Overview' && <Overview docs={docs} stats={stats} totalMemtable={totalMemtable} replicaVectors={totalReplicaVectors} onNavigate={setPage} onBenchmark={runBenchmark} benchmark={benchmark} progress={progress} busy={busy} />}
          {page === 'Search' && <SearchPage searchText={searchText} setSearchText={setSearchText} topK={topK} setTopK={setTopK} efSearch={efSearch} setEfSearch={setEfSearch} runSearch={runSearch} results={searchResults} busy={searchBusy} searched={searchPerformed} />}
          {page === 'Documents' && <DocumentsPage docs={docs} replicaVectors={totalReplicaVectors} filteredDocs={filteredDocs} filter={filter} setFilter={setFilter} bulkItems={bulkItems} setBulkItems={setBulkItems} uploadMode={uploadMode} setUploadMode={setUploadMode} pastedText={pastedText} setPastedText={setPastedText} handleBulkFile={handleBulkFile} handleBulkPaste={handleBulkPaste} runBulkInsert={runBulkInsert} busy={busy} progress={progress} fileInput={fileInput} onDelete={deleteOne} onClear={clearAll} onDownload={downloadCsv} docText={docText} setDocText={setDocText} docId={docId} setDocId={setDocId} onSingleInsert={handleSingleInsert} />}
          {page === 'Benchmark' && <BenchmarkPage benchmark={benchmark} config={benchmarkConfig} setConfig={setBenchmarkConfig} run={runBenchmark} busy={busy} progress={progress} docs={docs} replicaVectors={totalReplicaVectors} />}
        </div>
        <footer className="page-footer"><span>VECTORFORGE / DISTRIBUTED VECTOR DATABASE</span><span>JAVA HNSW · WAL STORAGE · CONSISTENT HASHING</span></footer>
      </main>

      {notice && <div className={`toast ${notice.kind}`}><span className="toast-icon">{notice.kind === 'success' ? <Check size={16} /> : <CircleHelp size={16} />}</span><span>{notice.message}</span><button onClick={() => setNotice(null)} aria-label="Dismiss"><X size={14} /></button></div>}
      {busy && progress && <div className="task-dock"><div className="task-icon"><LoaderCircle size={17} className="spin" /></div><div className="task-copy"><strong>{progress.label}</strong><span>{progress.total ? `${formatNumber(progress.done)} / ${formatNumber(progress.total)}` : modelState === 'loading' ? `Embedding model loading ${modelProgress || ''}%` : 'Preparing browser embedding model'}</span><div className="task-track"><i style={{ width: `${progress.total ? Math.min(100, progress.done / progress.total * 100) : modelProgress}%` }} /></div></div><button onClick={() => { if (fileInput.current) fileInput.current.value = ''; }} title="Task is running"><Activity size={15} /></button></div>}
      <div className={`boot-scrim ${boot === 'ready' ? 'hidden' : ''}`}><div className="boot-logo"><Workflow size={24} /></div><strong>Warming up your workspace</strong><span>Restoring locally saved document text…</span></div>
    </div>
  );
}

function PageHeading({ eyebrow, title, description, action }) {
  return <div className="page-heading"><div><div className="eyebrow">{eyebrow}</div><h1>{title}</h1><p>{description}</p></div>{action && <div className="heading-action">{action}</div>}</div>;
}

function StatBlock({ label, value, foot, icon: Icon, tone = '' }) {
  return <div className={`stat-block ${tone}`}><div className="stat-top"><span>{label}</span><Icon size={17} /></div><strong>{value}</strong><small>{foot}</small></div>;
}

function Overview({ docs, stats, totalMemtable, replicaVectors, onNavigate, onBenchmark, benchmark, progress, busy }) {
  const nodes = stats?.nodes || [];
  return <>
    <PageHeading eyebrow="SYSTEM OVERVIEW / LIVE" title="Your vector engine, at a glance." description="One coordinator. Three Java shards. Durable writes and approximate search, surfaced live." action={<button className="button button-ink" onClick={() => onNavigate('Documents')}><Plus size={16} /> Add documents</button>} />
    <section className="metric-grid">
      <StatBlock label="LOCAL DOCUMENTS" value={formatNumber(docs.length)} foot="Text records in this browser" icon={FileText} />
      <StatBlock label="REPLICATED VECTORS" value={formatNumber(replicaVectors)} foot="Physical copies across shards" icon={Boxes} tone="lime" />
      <StatBlock label="MEMTABLE RESIDENT" value={formatBytes(totalMemtable)} foot="Across all healthy nodes" icon={Cpu} />
      <StatBlock label="ACTIVE SHARDS" value={`${String(stats?.nodes?.filter(node => node.status === 'healthy').length || 0).padStart(2, '0')} / 03`} foot="Two replicas per logical write" icon={Network} tone="coral" />
    </section>

    <div className="section-row"><div><div className="eyebrow">PLACEMENT / STORAGE</div><h2>Shard health</h2></div><span className="updated-at"><span className="live-dot online" /> AUTO-REFRESH / 4 SEC</span></div>
    <section className="node-grid">
      {(nodes.length ? nodes : [{ id: 'node-1' }, { id: 'node-2' }, { id: 'node-3' }]).map((node, index) => <article className="node-panel" key={node.id}>
        <div className="node-topline"><span className="node-index">0{index + 1}</span><span className={`health-pill ${node.status === 'healthy' ? 'ok' : ''}`}><i />{node.status || 'CONNECTING'}</span></div>
        <div className="node-title"><div><h3>{node.id}</h3><span>JAVA SHARD / HASH RING MEMBER</span></div><div className="node-glyph"><Database size={19} /></div></div>
        <div className="node-meter"><div className="meter-label"><span>MEMTABLE</span><b>{formatBytes(node.memtable_bytes)}</b></div><div className="meter-track"><i style={{ width: `${Math.min(100, (node.memtable_bytes || 0) / (64 * 1024 * 1024) * 100)}%` }} /></div></div>
        <div className="node-bottom"><span><HardDrive size={14} /> {formatNumber(node.sstable_count)} SSTABLES</span><strong>{formatNumber(node.vector_count)} vectors</strong></div>
      </article>)}
    </section>

    <section className="overview-bottom">
      <div className="quick-panel">
        <div className="eyebrow">ACTIONS / QUICK ACCESS</div><h2>Put the engine to work.</h2>
        <div className="quick-actions">
          <button onClick={() => onNavigate('Search')}><span className="quick-icon"><Search size={18} /></span><span><b>Semantic search</b><small>Explore nearest neighbors</small></span><ArrowUpRight size={16} /></button>
          <button onClick={() => onNavigate('Documents')}><span className="quick-icon lime-icon"><Upload size={18} /></span><span><b>Bulk ingest</b><small>TXT or CSV, browser embedded</small></span><ArrowUpRight size={16} /></button>
          <button onClick={() => onNavigate('Benchmark')}><span className="quick-icon coral-icon"><Gauge size={18} /></span><span><b>Run benchmark</b><small>Java HNSW vs Java exact KNN</small></span><ArrowUpRight size={16} /></button>
        </div>
      </div>
      <div className="efficiency-panel"><div className="eyebrow">WRITE PATH / LIVE ARCHITECTURE</div><h2>Durable before searchable.</h2><div className="write-flow"><span>UPSERT</span><i /><span>WAL</span><i /><span>MEMTABLE</span><i /><span>HNSW</span></div><p>Coordinator hashes each ID, quorum writes persist to shard WALs, and nearest-neighbor queries fan out in parallel.</p><div className="efficiency-foot"><ShieldCheck size={16} /> WAL-FIRST DURABILITY <span>·</span> PARALLEL SEARCH <span>·</span> TOP-K MERGE</div></div>
    </section>
    {benchmark && <section className="last-benchmark"><span><Activity size={16} /> LAST BENCHMARK</span><strong>{benchmark.rows[0]?.recall ? `${(benchmark.rows[0].recall * 100).toFixed(1)}% recall` : 'Finished'}</strong><span>{formatNumber(benchmark.docs)} vectors / {benchmark.queries} queries</span><button className="text-button" onClick={() => onNavigate('Benchmark')}>Open report <ChevronRight size={14} /></button></section>}
    {busy && progress && <div className="inline-progress"><div><span>{progress.label}</span><b>{Math.round((progress.done / Math.max(1, progress.total)) * 100)}%</b></div><div className="progress-track"><i style={{ width: `${progress.done / Math.max(1, progress.total) * 100}%` }} /></div></div>}
  </>;
}

function SearchPage({ searchText, setSearchText, topK, setTopK, efSearch, setEfSearch, runSearch, results, busy, searched }) {
  return <>
    <PageHeading eyebrow="QUERY / APPROXIMATE NEAREST NEIGHBORS" title="Search by meaning." description="Embed a question in your browser, then scatter the query to Java HNSW shards." />
    <form className="search-console" onSubmit={runSearch}>
      <div className="search-glyph"><Search size={20} /></div>
      <textarea value={searchText} onChange={event => setSearchText(event.target.value)} placeholder="What are you looking for? Try a topic, phrase, or question…" rows={3} />
      <div className="search-controls"><label>TOP K<select value={topK} onChange={event => setTopK(event.target.value)}>{[5, 10, 20, 50].map(value => <option key={value}>{value}</option>)}</select></label><label>EF SEARCH<input type="number" min="1" max="1000" value={efSearch} onChange={event => setEfSearch(event.target.value)} /></label><button className="button button-lime" disabled={busy}><Search size={15} /> {busy ? 'Searching…' : 'Search cluster'}</button></div>
    </form>
    <div className="results-heading"><div><div className="eyebrow">RESULT SET / JAVA HNSW</div><h2>{results.length ? `${results.length} nearest documents` : 'Results appear here'}</h2></div>{results.length > 0 && <span className="results-note">LOWER L2 DISTANCE = CLOSER</span>}</div>
    {results.length > 0 ? <div className="result-list">{results.map((result, index) => <article className="result-row" key={result.id}><div className="result-rank">{String(index + 1).padStart(2, '0')}</div><div className="result-content"><div className="result-id">{result.id}</div><p>{result.text || '(text not in local document cache - document was indexed in another session)'}</p></div><div className="result-score"><span>DISTANCE</span><strong>{Number(result.distance).toFixed(5)}</strong></div></article>)}</div> : searched ? <div className="empty-state"><div className="empty-mark"><Search size={24} /></div><strong>No results found</strong><p>The Java cluster returned 0 results. Make sure the cluster is running and has indexed documents.</p></div> : <div className="empty-state"><div className="empty-mark"><Search size={24} /></div><strong>Waiting for a query</strong><p>Type a question or phrase above. The browser will embed it locally and scatter the query to Java HNSW shards.</p></div>}
  </>;
}

function DocumentsPage({ docs, replicaVectors, filteredDocs, filter, setFilter, bulkItems, uploadMode, setUploadMode, pastedText, setPastedText, handleBulkFile, handleBulkPaste, runBulkInsert, busy, progress, fileInput, onDelete, onClear, onDownload, docText, setDocText, docId, setDocId, onSingleInsert }) {
  return <>
    <PageHeading eyebrow="INGEST / BROWSE / DELETE" title="Documents in the forge." description="The browser owns source text. The Java cluster owns vectors, replicas, and search." action={<button className="button button-quiet" onClick={onDownload}><ArrowDownToLine size={15} /> Export CSV</button>} />
    <section className="ingest-grid">
      <article className="ingest-panel single-panel"><div className="panel-kicker"><span>01</span> SINGLE RECORD</div><h2>Forge a document</h2><form onSubmit={onSingleInsert}><label className="field-label">SOURCE TEXT</label><textarea className="document-input" value={docText} onChange={event => setDocText(event.target.value)} placeholder="Paste the text you want to index…" rows={5} /><div className="field-row"><label className="field-label">DOCUMENT ID <span>OPTIONAL</span></label><input className="text-input" value={docId} onChange={event => setDocId(event.target.value)} placeholder="auto-generated UUID" /></div><button className="button button-ink full-button" disabled={busy}><Zap size={15} /> Embed & index</button></form></article>
      <article className="ingest-panel bulk-panel"><div className="panel-kicker"><span>02</span> BULK INGEST</div><h2>Load a collection</h2><div className="mode-switch"><button className={uploadMode === 'file' ? 'selected' : ''} onClick={() => setUploadMode('file')}><Upload size={14} /> File</button><button className={uploadMode === 'paste' ? 'selected' : ''} onClick={() => setUploadMode('paste')}><FileText size={14} /> Paste</button></div>{uploadMode === 'file' ? <button className="dropzone" onClick={() => fileInput.current?.click()}><input ref={fileInput} type="file" accept=".txt,.csv,text/plain,text/csv" hidden onChange={event => handleBulkFile(event.target.files?.[0])} /><span className="drop-icon"><Upload size={19} /></span><b>Choose a TXT or CSV file</b><small>TXT: one document per line · CSV: text column, optional id</small>{bulkItems.length > 0 && <em>{formatNumber(bulkItems.length)} documents ready</em>}</button> : <><textarea className="paste-input" value={pastedText} onChange={event => setPastedText(event.target.value)} placeholder="One document per line…" /><button className="text-button" onClick={handleBulkPaste}>Prepare paste <ChevronRight size={14} /></button></>}{bulkItems.length > 0 && <div className="bulk-footer"><span>{formatNumber(bulkItems.length)} records staged</span><button className="button button-lime" onClick={runBulkInsert} disabled={busy}><Zap size={15} /> Start ingest</button></div>}{busy && progress && <div className="ingest-progress"><div><span>{progress.label}</span><b>{Math.round(progress.done / Math.max(1, progress.total) * 100)}%</b></div><div className="progress-track"><i style={{ width: `${progress.done / Math.max(1, progress.total) * 100}%` }} /></div></div>}</article>
    </section>
    <section className="library-section">
      <div className="section-row">
        <div>
          <div className="eyebrow">LOCAL TEXT CACHE / INDEXEDDB</div>
          <h2>Document library <span className="count-badge">{formatNumber(docs.length)}</span></h2>
        </div>
        <button className="button button-danger-quiet" onClick={onClear} disabled={(!docs.length && !replicaVectors) || busy}>
          <Trash2 size={14} /> Clear all cluster data
        </button>
      </div>
      <div className="library-toolbar">
        <div className="filter-box"><Search size={15} /><input value={filter} onChange={event => setFilter(event.target.value)} placeholder="Filter by text or document ID" /></div>
        <span>Showing {formatNumber(filteredDocs.length)} of {formatNumber(docs.length)} · {formatNumber(replicaVectors)} cluster copies</span>
      </div>
      {filteredDocs.length ? <div className="document-list">{filteredDocs.map(item => <article className="document-row" key={item.id}><div className="doc-bullet"><FileText size={15} /></div><div className="doc-body"><strong>{item.text.slice(0, 130) || 'Empty text'}{item.text.length > 130 ? '…' : ''}</strong><span>{item.id}</span></div><button className="row-delete" title="Delete document and replicated vectors" onClick={() => onDelete(item)}><Trash2 size={15} /></button></article>)}</div> : <div className="empty-state compact"><strong>No matching documents</strong><p>Import text above or change the filter.</p></div>}
    </section>
  </>;
}

function BenchmarkPage({ benchmark, config, setConfig, run, busy, progress, docs, replicaVectors }) {
  return <>
    <PageHeading eyebrow="PERFORMANCE LAB / JAVA ONLY" title="Measure the search engine." description="Compare distributed Java HNSW against exact Java brute force on the same held-out queries." action={<button className="button button-ink" onClick={run} disabled={busy}><Activity size={15} /> {busy ? 'Benchmark running' : 'Run benchmark'}</button>} />
    <div className="bench-toolbar"><div className="bench-control"><span>TOP K</span><select value={config.k} onChange={event => setConfig({ ...config, k: Number(event.target.value) })}>{[5, 10, 20].map(value => <option key={value} value={value}>{value}</option>)}</select></div><div className="bench-control"><span>HELD-OUT QUERIES</span><select value={config.queries} onChange={event => setConfig({ ...config, queries: Number(event.target.value) })}>{[10, 20, 30, 50].map(value => <option key={value} value={value}>{value}</option>)}</select></div><div className="bench-dataset"><Database size={16} /><span><b>{formatNumber(docs.length)} cached texts</b><small>{formatNumber(replicaVectors)} physical shard copies</small></span></div>{busy && progress && <div className="bench-progress-label"><LoaderCircle size={15} className="spin" /> {progress.label}</div>}</div>
    {benchmark ? <>
      <div className="bench-summary"><div><span>EXACT BASELINE</span><strong>{benchmark.baseline.toFixed(2)}<small> ms / query</small></strong></div><div><span>BEST HNSW RECALL</span><strong>{(Math.max(...benchmark.rows.map(item => item.recall)) * 100).toFixed(1)}<small>% @K</small></strong></div><div><span>BEST SPEEDUP</span><strong>{Math.max(...benchmark.rows.map(item => item.speedup)).toFixed(2)}<small>× faster</small></strong></div><div><span>QUERY SAMPLE</span><strong>{benchmark.queries}<small> queries · K={benchmark.k}</small></strong></div></div>
      <section className="chart-panel"><div className="chart-heading"><div><div className="eyebrow">QUALITY / RESPONSE TIME</div><h2>Recall versus latency</h2></div><div className="chart-legend"><span><i className="legend-hnsw" />JAVA HNSW</span><span><i className="legend-exact" />EXACT BASELINE</span></div></div><RecallChart rows={benchmark.rows} /></section>
      <section className="benchmark-table"><div className="table-heading"><div><div className="eyebrow">PARAMETER SWEEP</div><h2>Search effort report</h2></div><span>Java cluster · {benchmark.date.toLocaleTimeString()}</span></div><div className="table-scroll"><table><thead><tr><th>EF_SEARCH</th><th>RECALL@{benchmark.k}</th><th>AVG LATENCY</th><th>VS EXACT</th><th>QUALITY</th></tr></thead><tbody>{benchmark.rows.map(row => <tr key={row.ef}><td><code>{row.ef}</code></td><td><strong>{(row.recall * 100).toFixed(1)}%</strong></td><td>{row.latency.toFixed(2)} ms</td><td className={row.speedup >= 1 ? 'positive' : 'negative'}>{row.speedup.toFixed(2)}×</td><td><div className="quality-track"><i style={{ width: `${row.recall * 100}%` }} /></div></td></tr>)}<tr className="exact-row"><td>BRUTE FORCE</td><td>100.0%</td><td>{benchmark.baseline.toFixed(2)} ms</td><td>1.00×</td><td><div className="quality-track"><i style={{ width: '100%' }} /></div></td></tr></tbody></table></div></section>
    </> : <div className="benchmark-intro"><div className="bench-emblem"><Gauge size={28} /></div><div><h2>Run a real Java benchmark.</h2><p>The browser embeds a small held-out query set. Exact ground truth and HNSW results are both measured through the Java coordinator and remote shards.</p><div className="bench-method"><span><Check size={14} /> Java BruteForceIndex baseline</span><span><Check size={14} /> Java HNSW shard fan-out</span><span><Check size={14} /> Recall@K + average end-to-end latency</span></div></div><button className="button button-lime" onClick={run} disabled={busy}><Activity size={15} /> Start benchmark</button></div>}
  </>;
}

createRoot(document.getElementById('root')).render(<App />);
