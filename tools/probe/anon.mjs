import { request, jar } from './probe.mjs';
import crypto from 'node:crypto';

// 网易云匿名注册：username = base64(deviceId + " " + md5(deviceId + " " + presetKey))
const deviceId = crypto.randomBytes(16).toString('hex').toUpperCase();
const preset = '0CoJUm6Qyw8W8jud';
const digest = crypto.createHash('md5').update(deviceId + ' ' + preset).digest('hex');
const username = Buffer.from(deviceId + ' ' + digest).toString('base64');

console.log('deviceId =', deviceId);
try {
  const res = await request('/api/register/anonimous', { username });
  console.log('HTTP', res.status, 'code =', res.json?.code, res.json?.message || '');
  const sc = res.headers?.['set-cookie'];
  console.log('set-cookie:', sc ? sc.map(s => s.split(';')[0]) : 'NONE');
  console.log('jar 里的 cookie:', [...jar.keys()].join(', ') || '(空)');
} catch (e) {
  console.error('ERR', e.message);
}
