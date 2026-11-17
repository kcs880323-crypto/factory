// PC 설치판(Windows) 묶음 만들기.
//
//   node scripts/build-desktop.mjs   → dist-desktop/PartnersManager/ + dist-desktop/partners-manager-pc-<날짜>.zip
//
// 묶음 = 웹 빌드(web/) + 쿠팡·네이버 중계(api/) + 이 PC 전용 서버(server.cjs) + 실행기·설치기(desktop/).
// 서버는 127.0.0.1에서만 열리고, 앱 창(Edge 앱 모드)이 1분마다 신호를 보내 창을 닫으면 10분 뒤 꺼진다.
import { execFileSync, spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const app = join(dirname(fileURLToPath(import.meta.url)), '..');
const outRoot = join(app, 'dist-desktop');
const pkg = join(outRoot, 'PartnersManager');
const web = join(pkg, 'web');

rmSync(outRoot, { recursive: true, force: true });
mkdirSync(pkg, { recursive: true });

const r = spawnSync('npx', ['expo', 'export', '-p', 'web', '--clear', '--output-dir', web], {
  cwd: app,
  stdio: 'inherit',
  env: { ...process.env, EXPO_PUBLIC_DESKTOP: '1', EXPO_PUBLIC_HOSTING: '' },
  shell: process.platform === 'win32',
});
if (r.status !== 0) process.exit(r.status ?? 1);

// 앱 창이 열려 있는 동안 서버에 '살아 있음' 신호 (창을 닫으면 서버가 10분 뒤 스스로 꺼진다)
const indexPath = join(web, 'index.html');
const beat =
  "<script>(function(){function a(){try{fetch('/__alive',{method:'POST',keepalive:true}).catch(function(){})}catch(e){}}a();setInterval(a,60000)})();</script>";
const html = readFileSync(indexPath, 'utf8');
if (!html.includes('</body>')) throw new Error('index.html 모양이 달라요');
writeFileSync(indexPath, html.replace('</body>', `${beat}</body>`));

mkdirSync(join(pkg, 'api'));
for (const f of ['coupang.js', 'naver.js']) copyFileSync(join(app, 'api', f), join(pkg, 'api', f));
for (const f of readdirSync(join(app, 'desktop'))) copyFileSync(join(app, 'desktop', f), join(pkg, f));
writeFileSync(join(pkg, 'package.json'), `${JSON.stringify({ name: 'partners-manager-pc', private: true, type: 'commonjs' }, null, 2)}\n`);

const kst = new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10);
let commit = '';
try {
  commit = execFileSync('git', ['rev-parse', '--short', 'HEAD'], { cwd: app }).toString().trim();
} catch {
  commit = '';
}
writeFileSync(join(pkg, 'VERSION.txt'), `${kst}${commit ? ` (${commit})` : ''}\r\n`);

const zipName = `partners-manager-pc-${kst}.zip`;
const z = spawnSync('zip', ['-r', '-X', '-q', zipName, 'PartnersManager'], { cwd: outRoot, stdio: 'inherit' });
if (z.status !== 0) {
  console.error('zip 명령이 없어 압축은 건너뛰었어요. dist-desktop/PartnersManager 폴더를 그대로 쓰면 돼요.');
} else {
  console.log(`\n${join('dist-desktop', zipName)} (${(statSync(join(outRoot, zipName)).size / 1024 / 1024).toFixed(2)}MB)`);
}
if (!existsSync(join(pkg, 'web', 'index.html'))) throw new Error('web/index.html 이 없어요');
