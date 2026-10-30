/** Explicit offline orchestration. Real React panel, browser WebSocket and server
 * RPC/CAS; only physical HMD/IMU and OS output transports are test boundaries. */
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { createSocket } from 'node:dgram';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '@playwright/test';
import { createServer } from 'vite';
import react from '@vitejs/plugin-react';
import { MonakaInputSocket } from '../../src/monaka-input/socket.js';

const [readyPath, planPath, receiptPath, mode] = process.argv.slice(2);
assert.ok(readyPath && planPath && receiptPath && ['save', 'reload'].includes(mode));
const ready = JSON.parse(await readFile(readyPath, 'utf8'));
const plan = JSON.parse(await readFile(planPath, 'utf8')) as {
  first: string[]; restart: string[];
  bindings: { body: string; publisher: string; source: string; tracker: string }[];
};
const url = `ws://127.0.0.1:${ready.ws_port}`;
const api = new MonakaInputSocket(url);
const datagram = createSocket('udp4');
const sleep = (ms: number) => new Promise<void>((done) => setTimeout(done, ms));
const controlRequests: {op:string; fields:Record<string,unknown>}[] = [];
async function control(op: string, fields: Record<string, unknown> = {}) {
  controlRequests.push({op,fields});
  return new Promise<any>((accept, reject) => {
    const ws = new WebSocket(url);
    const timer = setTimeout(() => { ws.close(); reject(new Error('Owned fixture control deadline')); }, 10000);
    ws.onopen = () => ws.send(JSON.stringify({type: 'normal_fixture_control_v1', op, ...fields}));
    ws.onmessage = (event) => { const response = JSON.parse(String(event.data));
      if(response.type !== 'normal_fixture_control_v1') return;
      clearTimeout(timer); ws.close(); accept(response.proof);
    };
    ws.onerror = () => { clearTimeout(timer); ws.close(); reject(new Error('Owned fixture control failed')); };
  });
}
async function send(wire: string) {
  // The captured wire string is never decoded/re-encoded for transport.
  const bytes = Buffer.from(wire, 'utf8');
  await new Promise<void>((accept, reject) => datagram.send(bytes, ready.udp_port, '127.0.0.1', (error) => error ? reject(error) : accept()));
}
const samples: any[] = [];
const traffic: {direction: string; payload: string}[] = [];
const errors: string[] = [];
// Compiled file lives in gui/build/bridge-tests/tests/monaka-input; locate the
// source GUI root explicitly rather than serving a second copy of product UI.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..');
// Tailwind's existing PostCSS configuration resolves its theme from cwd.
// This changes only the freshly owned Node fixture process.
process.chdir(root);
const vite = await createServer({root, cacheDir:receiptPath+'.vite-cache', configFile: false, plugins: [react()], resolve: {alias: {'@': resolve(root, 'src')}}, server: {host:'127.0.0.1', port:0, strictPort:false}});
await vite.listen();
const address = vite.httpServer!.address(); assert.ok(address && typeof address !== 'string');
const browser = await chromium.launch({channel:'msedge', headless:true});
const page = await browser.newPage({viewport:{width:1200,height:1000}});
page.on('pageerror', (error) => errors.push(error.message));
page.on('websocket', (socket) => {
  socket.on('framesent', (frame) => { if(typeof frame.payload === 'string') traffic.push({direction:'request',payload:frame.payload}); });
  socket.on('framereceived', (frame) => { if(typeof frame.payload === 'string') traffic.push({direction:'response',payload:frame.payload}); });
});
let result = 'FAIL';
try {
  const before = await control('proof');
  assert.ok(before.virtual_trackers.length > 0);
  assert.equal(before.position?.measured_teacher ?? null, null, 'New consumer must not reuse an old Main sample');
  await control('imu', {enabled:true,yaw:.35}); // independent chosen Q, never derived from a teacher
  const rows = mode === 'save' ? plan.first : plan.restart;
  const initial = new Set<number>();
  for(const binding of plan.bindings) {
    const index = rows.findIndex((wire) => { const p=JSON.parse(wire); return p.source_id === binding.source && p.validity.position; });
    assert.ok(index >= 0); initial.add(index); await send(rows[index]);
  }
  await sleep(80);
  await page.goto(`http://127.0.0.1:${address.port}/tests/input-smoke/index.html?ip=127.0.0.1&port=${ready.ws_port}`);
  const body = page.getByLabel('Input body target', {exact:true});
  const q = page.getByLabel('Q Source', {exact:true});
  const six = page.getByLabel('6DoF Source', {exact:true});
  const save = page.getByRole('button', {name:'Save input choices', exact:true});
  const reload = page.getByRole('button', {name:'Reload input choices', exact:true});
  await expect(q).toBeEnabled();
  const snapshot = await api.request({action:'snapshot'});
  for(let i=0;i<plan.bindings.length;i++) {
    const binding=plan.bindings[i];
    const candidate=snapshot.candidates.find((c) => c.publisher===binding.publisher && c.source===binding.source && c.tracker===binding.tracker);
    assert.ok(candidate?.sixDofSelectable, 'Exact public opaque candidate absent');
    const qSource=ready.imu_sources[i];
    assert.ok(snapshot.candidates.some((c) => c.id===qSource && c.qSelectable));
    await body.selectOption(binding.body);
    if(mode==='save') {
      await q.selectOption(qSource); await six.selectOption(candidate.id); await save.click();
      await expect(save).toBeDisabled(); await expect(page.getByRole('alert')).toHaveCount(0);
    }
    await reload.click(); await expect(q).toHaveValue(qSource); await expect(six).toHaveValue(candidate.id);
  }
  const saved=await api.request({action:'snapshot'});
  assert.equal(saved.assignments.length, 2);
  const configHash=(await control('proof')).config_sha256;
  await page.screenshot({path:receiptPath+'.png',fullPage:true});
  let learned = false, movedIndependentQ = false;
  async function play(wires: string[], skip = new Set<number>()) {
    for(let i=0;i<wires.length;i++) {
      if(!skip.has(i)) await send(wires[i]);
      await sleep(60);
      if(i%4===0) {
        const proof=await control('proof'); samples.push(proof);
        learned ||= proof.position?.measured_teacher != null;
        if(learned && !movedIndependentQ && proof.position?.continuity==='FALLBACK_ACTIVE') {
          // A second independently chosen physical-Q rotation during Main loss
          // gives reacquisition a real numeric transition; teacher wire is untouched.
          await control('imu',{enabled:true,yaw:.7}); movedIndependentQ=true;
        }
      }
    }
  }
  await play(rows, initial);
  await sleep(650); samples.push(await control('proof')); // actual expiry, fresh Q/HMD continue
  if(mode==='save') await play(plan.restart); // producer + Bridge fresh session after source restart
  await sleep(150); samples.push(await control('proof'));
  assert.equal((await control('proof')).config_sha256,configHash);
  // Learning decisions can be UPDATED for a single 20ms tick and HELD by the
  // later RPC snapshot. The Kotlin oracle requires UPDATED and numeric error
  // improvement in the complete same-frame stream, not this sparse sampling.
  assert.ok(samples.some((p)=>p.position?.continuity==='FALLBACK_ACTIVE'),'Actual solver fallback not observed');
  if(mode==='save') assert.ok(samples.some((p)=>p.position?.continuity==='REACQUIRING'),'Fresh Main reacquisition not observed');
  assert.deepEqual(errors,[]);
  const requests=traffic.filter((r)=>r.direction==='request').map((r)=>JSON.parse(r.payload));
  assert.equal(requests.filter((r)=>r.type==='monaka_inputs_v1'&&r.action==='save').length,mode==='save'?2:0,'Save must come from the actual React handlers');
  result='PASS';
} finally {
  await writeFile(receiptPath,JSON.stringify({result,mode,boundary:'actual MonakaInputSettings React DOM handlers -> production controller/socket -> actual RPC/CAS; public wire exact; explicit physical-category software inputs',samples,traffic,controlRequests,errors,physical_slime_udp:'NOT_RUN',hardware:'NOT_RUN'},null,2));
  try { await control('stop'); } finally { api.close(); datagram.close(); await browser.close(); await vite.close(); }
}
console.log(`PASS actual React/RPC/CAS normal consumer ${mode}`);
