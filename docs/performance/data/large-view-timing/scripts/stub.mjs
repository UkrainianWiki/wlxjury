// A local stand-in for upload.wikimedia.org: https on a local port, one noise PNG of about IMG_KB kilobytes for every
// path, with Wikimedia's cache and Timing-Allow-Origin headers. Chromium maps the host to it (--host-resolver-rules).
import https from 'node:https'; import fs from 'node:fs'; import zlib from 'node:zlib'; import crypto from 'node:crypto'; import path from 'node:path';
export function noisePng(kb) {
  const w = 400, h = Math.max(8, Math.round((kb * 1024) / (w * 3)));
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 3 + 1)] = 0; crypto.randomFillSync(raw, y * (w * 3 + 1) + 1, w * 3); }
  const crc = (b) => { let c, t = []; for (let n = 0; n < 256; n++) { c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } let r = 0xffffffff; for (const x of b) r = t[(r ^ x) & 255] ^ (r >>> 8); return (r ^ 0xffffffff) >>> 0; };
  const chunk = (type, data) => { const l = Buffer.alloc(4); l.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 1 })), chunk('IEND', Buffer.alloc(0))]);
}
export async function startStub(dir, kb = 250) {
  const body = noisePng(kb); const seen = [];
  const srv = https.createServer({ key: fs.readFileSync(path.join(dir, 'key.pem')), cert: fs.readFileSync(path.join(dir, 'cert.pem')) }, (req, res) => {
    seen.push(req.url);
    res.writeHead(200, { 'content-type': 'image/png', 'content-length': body.length, 'cache-control': 'public, max-age=31536000', 'timing-allow-origin': '*', 'access-control-allow-origin': '*' });
    res.end(body);
  });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  return { port: srv.address().port, bytes: body.length, seen, close: () => srv.close() };
}
