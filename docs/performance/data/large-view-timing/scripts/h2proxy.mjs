// HTTP/2 (TLS) front for the local app, as Apache fronts it in production: a reverse proxy to the app's plain HTTP port.
import http2 from 'node:http2'; import http from 'node:http'; import fs from 'node:fs'; import path from 'node:path';
export function startH2Proxy(dir, targetPort) {
  const srv = http2.createSecureServer({ key: fs.readFileSync(path.join(dir, 'key.pem')), cert: fs.readFileSync(path.join(dir, 'cert.pem')), allowHTTP1: true }, (req, res) => {
    const headers = {}; for (const [k, v] of Object.entries(req.headers)) if (!k.startsWith(':') && !['connection', 'keep-alive', 'transfer-encoding', 'upgrade'].includes(k)) headers[k] = v;
    headers.host = 'localhost:' + targetPort; headers['x-forwarded-proto'] = 'https';
    const up = http.request({ host: '127.0.0.1', port: targetPort, method: req.method, path: req.url, headers, agent: new http.Agent({ keepAlive: true, maxSockets: 64 }) }, (r) => {
      const h = {}; for (const [k, v] of Object.entries(r.headers)) if (!['connection', 'keep-alive', 'transfer-encoding'].includes(k)) h[k] = v;
      res.writeHead(r.statusCode, h); r.pipe(res);
    });
    up.on('error', () => { res.statusCode = 502; res.end(); });
    req.pipe(up);
  });
  return new Promise((r) => srv.listen(0, '127.0.0.1', () => r({ port: srv.address().port, close: () => srv.close() })));
}
