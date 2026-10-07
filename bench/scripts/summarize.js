#!/usr/bin/env node
// Summarise benchmark runs (bench/scripts/run.sh results):
//   node summarize.js <results/label>... [--requests "cast vote,gallery page"]
// Per run and simulation: per request name count, KO, mean / p50 / p95 / p99 ms; the
// SHOW GLOBAL STATUS deltas that matter (table locks, key cache, buffer pool, temp
// tables, slow queries); CPU and memory from the samples.
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
let only = null;
const runs = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--requests') only = args[++i].split(',');
  else runs.push(args[i]);
}

const STATUS = ['Table_locks_waited', 'Table_locks_immediate', 'Key_reads', 'Key_read_requests',
  'Key_writes', 'Innodb_buffer_pool_reads', 'Innodb_buffer_pool_read_requests',
  'Innodb_row_lock_waits', 'Innodb_row_lock_time', 'Innodb_data_written', 'Innodb_os_log_written',
  'Created_tmp_disk_tables', 'Created_tmp_tables', 'Slow_queries', 'Questions',
  'Innodb_buffer_pool_pages_free', 'Innodb_buffer_pool_pages_data'];

function readStatus(f) {
  const m = {};
  if (!fs.existsSync(f)) return m;
  for (const line of fs.readFileSync(f, 'utf8').split('\n')) {
    const [k, v] = line.split('\t');
    if (k) m[k] = Number(v);
  }
  return m;
}

function requests(node, acc) {
  if (!node) return acc;
  if (node.type === 'REQUEST') acc.push(node);
  for (const c of Object.values(node.contents || {})) requests(c, acc);
  return acc;
}

function samples(f) {
  if (!fs.existsSync(f)) return null;
  const rows = fs.readFileSync(f, 'utf8').trim().split('\n').slice(1).map(l => l.split(','));
  const num = s => parseFloat(String(s).replace(/[^0-9.]/g, '')) || 0;
  const memMb = s => { const m = /([0-9.]+)\s*(GiB|MiB|KiB)/.exec(s || ''); if (!m) return 0;
    return Number(m[1]) * ({ GiB: 1024, MiB: 1, KiB: 1 / 1024 })[m[2]]; };
  const cpu = rows.map(r => num(r[1]));
  const mem = rows.map(r => memMb(r[2]));
  const db = rows.map(r => num(r[3]) / 1024), app = rows.map(r => num(r[4]) / 1024);
  const avg = a => a.length ? a.reduce((x, y) => x + y, 0) / a.length : 0;
  const max = a => a.length ? Math.max(...a) : 0;
  return { n: rows.length, cpuAvg: avg(cpu), cpuMax: max(cpu), memMax: max(mem), dbRssMax: max(db), appRssMax: max(app) };
}

for (const run of runs) {
  console.log(`\n=== ${path.basename(run)}`);
  const dirs = fs.readdirSync(run).filter(d => fs.existsSync(path.join(run, d, 'status-before.txt')));
  for (const d of dirs) {
    const dir = path.join(run, d);
    console.log(`-- ${d}`);
    const sf = path.join(dir, 'gatling', 'js', 'stats.json');
    if (fs.existsSync(sf)) {
      const stats = JSON.parse(fs.readFileSync(sf, 'utf8'));
      for (const r of requests(stats, [])) {
        if (only && !only.includes(r.name)) continue;
        const s = r.stats;
        console.log(`   ${r.name.padEnd(42)} n=${String(s.numberOfRequests.total).padStart(6)} ko=${String(s.numberOfRequests.ko).padStart(4)}` +
          `  mean=${String(s.meanResponseTime.total).padStart(6)}  p50=${String(s.percentiles1.total).padStart(6)}` +
          `  p95=${String(s.percentiles3.total).padStart(6)}  p99=${String(s.percentiles4.total).padStart(6)}`);
      }
    } else console.log('   (no Gatling stats)');
    const a = readStatus(path.join(dir, 'status-before.txt')), b = readStatus(path.join(dir, 'status-after.txt'));
    const delta = STATUS.filter(k => k in b).map(k => `${k}=${k.startsWith('Innodb_buffer_pool_pages') ? b[k] : b[k] - (a[k] || 0)}`);
    console.log(`   status: ${delta.join(' ')}`);
    const sm = samples(path.join(dir, 'samples.csv'));
    if (sm) console.log(`   container: cpu avg ${sm.cpuAvg.toFixed(0)}% max ${sm.cpuMax.toFixed(0)}% (of 200%), mem max ${sm.memMax.toFixed(0)} MB; ` +
      `mariadbd RSS max ${sm.dbRssMax.toFixed(0)} MB, java RSS max ${sm.appRssMax.toFixed(0)} MB (${sm.n} samples)`);
    for (const f of ['distribution-memory.txt', 'concurrent-distribution.txt']) {
      const p = path.join(dir, f);
      if (fs.existsSync(p)) console.log(fs.readFileSync(p, 'utf8').trim().split('\n').map(l => '   ' + l).join('\n'));
    }
  }
}

// The app's GC log (one per run): pauses, full GCs, heap after GC.
for (const run of runs) {
  const f = path.join(run, 'gc.log');
  if (!fs.existsSync(f)) continue;
  let pauses = 0, full = 0, maxAfter = 0, maxCommitted = 0, pauseMs = 0;
  for (const line of fs.readFileSync(f, 'utf8').split('\n')) {
    const m = /GC\(\d+\) Pause (Young|Full|Remark|Cleanup)[^\n]*?(\d+)M->(\d+)M\((\d+)M\) ([0-9.]+)ms/.exec(line);
    if (!m) continue;
    pauses++; if (m[1] === 'Full') full++;
    maxAfter = Math.max(maxAfter, Number(m[3])); maxCommitted = Math.max(maxCommitted, Number(m[4]));
    pauseMs += Number(m[5]);
  }
  console.log(`\n${path.basename(run)} GC: ${pauses} pauses (${full} full), ${pauseMs.toFixed(0)} ms in all; ` +
    `max heap after GC ${maxAfter} MB, max committed ${maxCommitted} MB`);
}
