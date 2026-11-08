import { createServer } from 'node:http';

// Contract probe only: never connects to the real API or database.
const calls = new Map();
createServer(async (request, response) => {
  const url = new URL(request.url, 'http://probe');
  response.setHeader('Content-Type', 'application/json');
  if (url.pathname === '/counts') return response.end(JSON.stringify(Object.fromEntries(calls)));
  calls.set(request.url, (calls.get(request.url) ?? 0) + 1);
  let body = '';
  for await (const chunk of request) body += chunk;
  if (url.pathname === '/api/v1/abort') return request.socket.destroy();
  if (url.pathname === '/api/v1/unavailable') {
    response.writeHead(503);
    return response.end(JSON.stringify({ code: 'SERVICE_UNAVAILABLE' }));
  }
  if (!url.pathname.startsWith('/api/v1/')) {
    response.writeHead(404);
    return response.end(JSON.stringify({ code: 'REQUEST_INVALID' }));
  }
  response.end(
    JSON.stringify({
      tag: process.env.RESPONSE_TAG,
      method: request.method,
      url: request.url,
      body,
      forwardedFor: request.headers['x-forwarded-for'],
    }),
  );
}).listen(8080, '0.0.0.0');
