import { request } from './probe.mjs';
const res = await request('/api/login/qrcode/unikey', { type: 1 });
console.log(JSON.stringify(res.json));
