const crypto = require('crypto');
const http = require('https');

const JWT_SECRET = process.env.JWT_SECRET;
if (!JWT_SECRET) {
  console.error("ERROR: JWT_SECRET environment variable is missing.");
  process.exit(1);
}
const baseUrl = process.argv[2] || 'https://seat-reservation-production-fee9.up.railway.app';
const TOTAL_REQUESTS = 30000;
const MAX_CONCURRENT = 5000;

function createJwt(subject, isAdmin = false) {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const payloadData = { sub: subject };
  if (isAdmin) payloadData.scope = 'admin';
  
  const payload = Buffer.from(JSON.stringify(payloadData)).toString('base64url');
  const signature = crypto.createHmac('sha256', JWT_SECRET).update(`${header}.${payload}`).digest('base64url');
  return `${header}.${payload}.${signature}`;
}

const adminToken = createJwt(crypto.randomUUID(), true);

function request(method, path, body, token) {
  return new Promise((resolve, reject) => {
    const url = new URL(baseUrl + path);
    const options = {
      method,
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      agent: new http.Agent({ keepAlive: true, maxSockets: 5000 })
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

// Shared State
const shows = []; // { id, allSeats: [], salesOpenAt: Date }
const activeReservations = []; // { reservationId, token }

const stats = {
  create_show_ok: 0,
  create_show_fail: 0,
  reserve_ok: 0,
  reserve_early_rejected: 0, // 409 sales_not_open
  reserve_conflict: 0, // 409/422 other
  reserve_fail: 0,
  cancel_ok: 0,
  cancel_fail: 0,
  rate_limit: 0,
  server_error: 0,
  client_error: 0
};

function recordGeneralError(status) {
  if (status === 429) stats.rate_limit++;
  else if (status >= 500) stats.server_error++;
}

async function taskCreateShow() {
  const isMultiTier = Math.random() > 0.5;
  // Sale opens randomly between now and 30 seconds from now
  const saleOpenDelayMs = Math.floor(Math.random() * 30000); 
  const salesOpenAt = new Date(Date.now() + saleOpenDelayMs);

  const payload = {
    name: `Show ${crypto.randomUUID().substring(0, 8)}`,
    price_paise: 500000,
    per_user_limit: 4,
    hold_ttl_sec: 300,
    salesOpenAt: salesOpenAt.toISOString()
  };
  
  let allSeats = [];
  
  if (isMultiTier) {
    const gold = generateSeats('G', 50);
    const silver = generateSeats('S', 100);
    allSeats = [...gold, ...silver];
    payload.tiers = [
      { name: 'Gold', price_paise: 600000, seats: gold },
      { name: 'Silver', price_paise: 300000, seats: silver }
    ];
  } else {
    allSeats = generateSeats('GEN-', 150);
    payload.seats = allSeats;
  }
  
  try {
    const res = await request('POST', '/shows', payload, adminToken);
    if (res.status === 201) {
      const showId = JSON.parse(res.body).id;
      shows.push({ id: showId, allSeats, salesOpenAt });
      stats.create_show_ok++;
    } else {
      recordGeneralError(res.status);
      stats.create_show_fail++;
    }
  } catch (err) {
    stats.client_error++;
  }
}

async function taskReserve() {
  if (shows.length === 0) return taskCreateShow();
  
  const show = shows[Math.floor(Math.random() * shows.length)];
  const numSeats = Math.floor(Math.random() * 4) + 1;
  const requestedSeats = [];
  for (let i = 0; i < numSeats; i++) {
    requestedSeats.push(show.allSeats[Math.floor(Math.random() * show.allSeats.length)]);
  }
  
  const userToken = createJwt(crypto.randomUUID());
  
  try {
    const res = await request('POST', `/shows/${show.id}/reserve`, {
      seats: requestedSeats,
      mode: 'reserve',
      idempotency_key: crypto.randomUUID()
    }, userToken);
    
    if (res.status === 201) {
      stats.reserve_ok++;
      const match = res.body.match(/"reservation_id"\s*:\s*"([^"]+)"/);
      if (match) {
        activeReservations.push({ reservationId: match[1], token: userToken });
      }
    } else if (res.status === 409 || res.status === 422) {
      if (res.body.includes('sales_not_open')) {
        stats.reserve_early_rejected++;
      } else {
        stats.reserve_conflict++;
      }
    } else {
      recordGeneralError(res.status);
      stats.reserve_fail++;
    }
  } catch (err) {
    stats.client_error++;
  }
}

async function taskCancel() {
  if (activeReservations.length === 0) return taskReserve();
  
  const idx = Math.floor(Math.random() * activeReservations.length);
  const { reservationId, token } = activeReservations.splice(idx, 1)[0];
  
  try {
    const res = await request('POST', `/reservations/${reservationId}/cancel`, {}, token);
    if (res.status === 200) {
      stats.cancel_ok++;
    } else {
      recordGeneralError(res.status);
      stats.cancel_fail++;
    }
  } catch (err) {
    stats.client_error++;
  }
}

async function run() {
  // Wait a few seconds before starting to ensure some initial shows are created
  console.log('Pre-provisioning a few shows...');
  for (let i = 0; i < 5; i++) {
    await taskCreateShow();
  }

  console.log(`Preparing to dispatch ${TOTAL_REQUESTS} mixed requests...`);
  console.log(`Max Concurrency: ${MAX_CONCURRENT}`);
  
  const tasks = [];
  for (let i = 0; i < TOTAL_REQUESTS; i++) {
    const r = Math.random();
    if (r < 0.01) tasks.push(taskCreateShow);      // 1% Creates Shows
    else if (r < 0.85) tasks.push(taskReserve);    // 84% Reserve Seats
    else tasks.push(taskCancel);                   // 15% Cancel Reservations
  }
  
  console.log('Task distribution generated. Starting load test...');
  
  let active = 0;
  let index = 0;
  let completed = 0;
  
  await new Promise(resolve => {
    function next() {
      if (completed === tasks.length) return resolve();
      
      while (active < MAX_CONCURRENT && index < tasks.length) {
        const taskFn = tasks[index++];
        active++;
        taskFn().finally(() => {
          active--;
          completed++;
          if (completed % 2500 === 0) {
            console.log(`Progress: ${completed} / ${TOTAL_REQUESTS} completed...`);
          }
          next();
        });
      }
    }
    next();
  });
  
  console.log('\n--- LOAD TEST COMPLETED ---');
  console.log('Results breakdown:');
  console.log(JSON.stringify(stats, null, 2));
}

run().catch(console.error);
