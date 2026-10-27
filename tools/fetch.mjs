// tools/fetch.mjs
// curl.exe is unusable on this machine (schannel SEC_E_NO_CREDENTIALS),
// so every download goes through here.
//
// Usage: node tools/fetch.mjs <url> <output-file> [--force]
//
// Features:
//   - follows redirects
//   - resumes partial files via Range
//   - retries up to 3 times
//   - skips files that are already complete (use --force to redownload)
//
// Note: this file is ESM and is read by Node as UTF-8, so non-ASCII text in
// comments is safe here (unlike .ps1 files on this machine).

import { createWriteStream } from 'node:fs';
import { mkdir, stat, unlink } from 'node:fs/promises';
import { dirname } from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const argv = process.argv.slice(2);
const force = argv.includes('--force');
const [url, out] = argv.filter((a) => a !== '--force');

if (!url || !out) {
  console.error('usage: node tools/fetch.mjs <url> <output-file> [--force]');
  process.exit(2);
}

const MB = 1048576;
const fmt = (n) => (n / MB).toFixed(1);

const sizeOf = async (p) => {
  try {
    return (await stat(p)).size;
  } catch {
    return 0;
  }
};

// A response that is content-encoded (e.g. gzip) reports the *compressed*
// content-length, which cannot be compared against decoded stream bytes.
// In that case we simply do not show a percentage.
const decodable = (res) => {
  const enc = res.headers.get('content-encoding');
  return !enc || enc === 'identity';
};

await mkdir(dirname(out), { recursive: true });

// Probe remote size so we can skip / resume.
let remoteSize = 0;
try {
  const head = await fetch(url, { headers: { Range: 'bytes=0-0' }, redirect: 'follow' });
  const cr = head.headers.get('content-range');
  if (cr && cr.includes('/')) {
    remoteSize = Number(cr.split('/')[1]) || 0;
  } else if (decodable(head)) {
    remoteSize = Number(head.headers.get('content-length') || 0);
  }
  // Drain and discard the probe body.
  await head.arrayBuffer().catch(() => {});
} catch {
  // Unknown size: continue without skip/resume support.
}

const localSize = await sizeOf(out);

if (!force && remoteSize > 0 && localSize === remoteSize) {
  console.log(`skip (already complete): ${out}  ${fmt(localSize)} MB`);
  process.exit(0);
}

let startAt = 0;
if (!force && localSize > 0 && remoteSize > localSize) {
  startAt = localSize;
  console.log(`partial file found (${fmt(localSize)} MB), resuming...`);
} else if (localSize > 0) {
  await unlink(out).catch(() => {});
}

const MAX_TRIES = 3;

for (let attempt = 1; attempt <= MAX_TRIES; attempt++) {
  try {
    const headers = {};
    if (startAt > 0) headers.Range = `bytes=${startAt}-`;

    const res = await fetch(url, { headers, redirect: 'follow' });
    if (!res.ok && res.status !== 206) throw new Error(`HTTP ${res.status}`);

    const knownTotal = decodable(res) ? startAt + Number(res.headers.get('content-length') || 0) : 0;

    console.log(`downloading: ${url}`);
    console.log(`  -> ${out}${knownTotal > 0 ? `  (${fmt(knownTotal)} MB)` : ''}`);

    let done = startAt;
    let nextMark = 10;

    const body = Readable.fromWeb(res.body);
    body.on('data', (chunk) => {
      done += chunk.length;
      if (knownTotal > 0) {
        const pct = Math.floor((done / knownTotal) * 100);
        if (pct >= nextMark && nextMark <= 100) {
          console.log(`  ${String(nextMark).padStart(3)}%   ${fmt(done)} MB`);
          nextMark += 10;
        }
      }
    });

    await pipeline(body, createWriteStream(out, { flags: startAt > 0 ? 'a' : 'w' }));

    const finalSize = await sizeOf(out);
    if (knownTotal > 0 && finalSize < knownTotal) {
      throw new Error(`incomplete: ${fmt(finalSize)} / ${fmt(knownTotal)} MB`);
    }

    console.log(`DONE  ${out}  ${fmt(finalSize)} MB`);
    process.exit(0);
  } catch (err) {
    console.error(`  attempt ${attempt}/${MAX_TRIES} failed: ${err.message}`);
    startAt = await sizeOf(out);
    if (attempt === MAX_TRIES) {
      console.error(`GIVE UP: ${url}`);
      process.exit(1);
    }
    await new Promise((r) => setTimeout(r, 2000 * attempt));
  }
}
