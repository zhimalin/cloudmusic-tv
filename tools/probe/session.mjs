import { request, jar } from './probe.mjs';
async function probe(name, api, data) {
  try {
    const res = await request(api, data || {});
    const sc = res.headers?.['set-cookie'];
    console.log(`${name}: HTTP ${res.status} code=${res.json?.code ?? '-'}  set-cookie=${sc ? sc.map(s=>s.split(';')[0]).join(' | ') : 'NONE'}`);
  } catch (e) {
    console.log(`${name}: ERR ${e.message}`);
  }
  console.log('   jar:', [...jar.keys()].join(', ') || '(空)');
}
await probe('need/login/forplay', '/api/need/login/forplay', {});
await probe('unikey', '/api/login/qrcode/unikey', { type: 1 });
await probe('batch', '/api/batch', {});
