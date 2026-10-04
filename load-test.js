const crypto = require('crypto');
const http = require('https'); // assuming https URL

const JWT_SECRET = 'dev-secret-key-at-least-32-bytes-long-for-hs256';
const baseUrl = process.argv[2] || 'https://seat-reservation-production-fee9.up.railway.app';

function createJwt(subject) {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const payload = Buffer.from(JSON.stringify({ sub: subject, scope: 'admin' })).toString('base64url');
  const signature = crypto.createHmac('sha256', JWT_SECRET).update(`${header}.${payload}`).digest('base64url');
  return `${header}.${payload}.${signature}`;
}

function request(method, path, body, token) {
  return new Promise((resolve, reject) => {
    const url = new URL(baseUrl + path);
    const options = {
      method,
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      }
    };
    const req = require(url.protocol.slice(0, -1)).request(url, options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => resolve({ status: res.statusCode, body: data }));
    });
    req.on('error', reject);
    if (body) req.write(JSON.stringify(body));
    req.end();
  });
}

async function run() {
  console.log(`Starting load test against ${baseUrl}...`);
  const adminToken = createJwt(crypto.randomUUID());

  // Provision show
  console.log('Provisioning show...');
  const showRes = await request('POST', '/shows', {
    name: `Load Test ${crypto.randomUUID()}`,
    price_paise: 10000,
    per_user_limit: 5,
    hold_ttl_sec: 300,
    seats: ['A1', 'A2', 'A3', 'A4', 'A5']
  }, adminToken);

  if (showRes.status !== 201) {
    console.error('Failed to provision show:', showRes.body);
    process.exit(1);
  }

  const showId = JSON.parse(showRes.body).id;
  console.log(`Show provisioned: ${showId}`);

  // Phase 1: Hot-seat storm
  console.log('Starting Phase 1: Hot-seat storm (2000 users competing for A1)...');
  const numUsers = 2000;
  const promises = [];
  const results = { 201: 0, 409: 0, 422: 0, 429: 0, 500: 0, other: 0, errors: 0 };

  for (let i = 0; i < numUsers; i++) {
    const userToken = createJwt(crypto.randomUUID());
    promises.push(request('POST', `/shows/${showId}/reserve`, {
      seats: ['A1'],
      mode: 'reserve',
      idempotency_key: crypto.randomUUID()
    }, userToken).then(res => {
      if (res.status === 201) results[201]++;
      else if (res.status === 409 || res.status === 422) results[409]++;
      else if (res.status === 429) results[429]++;
      else if (res.status >= 500) results[500]++;
      else results.other++;
    }).catch(err => {
      results.errors++;
    }));
  }

  await Promise.all(promises);

  console.log('\nResults:');
  console.log(`  201 Created (Success, expected exactly 1): ${results[201]}`);
  console.log(`  409/422 Conflict (Expected for losers):    ${results[409]}`);
  console.log(`  429 Rate Limited:                          ${results[429]}`);
  console.log(`  5xx Server Errors (Expected 0):            ${results[500]}`);
  console.log(`  Client Errors:                             ${results.errors}`);
  console.log(`  Other Status Codes:                        ${results.other}`);
  
  if (results[201] === 1 && results[500] === 0) {
    console.log('\n✅ CORRECTNESS BAR MET: Exactly one user got the seat, no server errors.');
  } else {
    console.log('\n❌ FAILED: Correctness invariants violated.');
  }
}

run().catch(console.error);
