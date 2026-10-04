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

const generateSeats = (prefix, count) => Array.from({length: count}, (_, i) => `${prefix}${i + 1}`);

async function run() {
  console.log(`Starting scenario test against ${baseUrl}...`);
  const adminToken = createJwt(crypto.randomUUID());

  const goldSeats = generateSeats('G', 300);
  const silverSeats = generateSeats('S', 400);
  const platSeats = generateSeats('P', 200);
  const diamondSeats = generateSeats('D', 100);
  const allSeats = [...goldSeats, ...silverSeats, ...platSeats, ...diamondSeats];

  console.log('Provisioning show with 1000 seats across 4 tiers...');
  const showRes = await request('POST', '/shows', {
    name: `Mega Concert ${crypto.randomUUID()}`,
    price_paise: 500000, // Base price (required by DTO)
    per_user_limit: 4,
    hold_ttl_sec: 300,
    tiers: [
      { name: 'Gold', price_paise: 500000, seats: goldSeats },
      { name: 'Silver', price_paise: 400000, seats: silverSeats },
      { name: 'Platinum', price_paise: 700000, seats: platSeats },
      { name: 'Diamond', price_paise: 1000000, seats: diamondSeats }
    ]
  }, adminToken);

  if (showRes.status !== 201) {
    console.error('Failed to provision show:', showRes.body);
    process.exit(1);
  }

  const showId = JSON.parse(showRes.body).id;
  console.log(`Show provisioned successfully: ${showId}`);

  const delayMs = 3 * 60 * 1000; // 3 minutes
  console.log(`\nSale goes live in 3 minutes. Waiting until ${new Date(Date.now() + delayMs).toLocaleTimeString()}...`);
  
  // We'll countdown every 30 seconds
  for (let i = 180; i > 0; i -= 30) {
    console.log(`... ${i} seconds remaining`);
    await new Promise(resolve => setTimeout(resolve, 30000));
  }
  
  console.log('\nSALE IS LIVE! 20,000 users storming the system...');
  
  const numUsers = 20000;
  const promises = [];
  const results = { 201: 0, 409: 0, 422: 0, 429: 0, 500: 0, other: 0, errors: 0 };

  for (let i = 0; i < numUsers; i++) {
    const userToken = createJwt(crypto.randomUUID());
    
    // Each user tries to grab 1-4 random seats
    const numSeatsToGrab = Math.floor(Math.random() * 4) + 1;
    const requestedSeats = [];
    for (let j = 0; j < numSeatsToGrab; j++) {
      requestedSeats.push(allSeats[Math.floor(Math.random() * allSeats.length)]);
    }

    promises.push(request('POST', `/shows/${showId}/reserve`, {
      seats: requestedSeats,
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

  console.log('\n--- Sale Completed! ---');
  console.log(`  201 Created (Successful orders): ${results[201]}`);
  console.log(`  409/422 Conflict (Failed/Sold out): ${results[409]}`);
  console.log(`  429 Rate Limited: ${results[429]}`);
  console.log(`  5xx Server Errors (Should be 0): ${results[500]}`);
  console.log(`  Client Errors: ${results.errors}`);
  console.log(`  Other Status Codes: ${results.other}`);
  
  // Reconcile
  console.log('\nReconciling show state...');
  const reconRes = await request('GET', `/shows/${showId}`, null, adminToken);
  if (reconRes.status === 200) {
    const showData = JSON.parse(reconRes.body);
    console.log(`  Total Seats: ${showData.total_seats}`);
    console.log(`  Seats Available: ${showData.seats_available}`);
    console.log(`  Seats Held: ${showData.seats_held}`);
    console.log(`  Seats Confirmed: ${showData.seats_confirmed}`);
    
    const invariantHolds = (showData.seats_available + showData.seats_held + showData.seats_confirmed === showData.total_seats);
    console.log(`  Invariant Holds (Available + Held + Confirmed == Total)? ${invariantHolds ? 'YES ✅' : 'NO ❌'}`);
  } else {
    console.log('Failed to fetch show state for reconciliation.');
  }
}

run().catch(console.error);
