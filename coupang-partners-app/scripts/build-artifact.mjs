// Claude 아티팩트(claude.ai에 올리는 비공개 웹페이지)용 빌드.
//
//   node scripts/build-artifact.mjs      → dist-artifact/ (index.html + js/… + assets/…)
//
// 아티팩트 화면은 (1) 어느 경로에서 열릴지 모르고 (2) 바깥 서버 요청을 막으며 (3) 페이지 본문만 받는다
// (<!doctype>, <html>, <head>, <body>는 올릴 때 덧씌워진다). 그래서:
// - 기준 경로를 자리표시자(/__CPBASE__)로 빌드한 뒤, 실행할 때 페이지가 열린 디렉터리로 바꿔 끼운다.
// - 스크립트는 상대 경로로 부른다.
// - API는 쓰지 않는다(EXPO_PUBLIC_HOSTING=artifact): 링크 보관함·글 만들기·예시 모드만.
import { spawnSync } from 'node:child_process';
import { mkdirSync, readdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const app = join(dirname(fileURLToPath(import.meta.url)), '..');
const out = join(app, 'dist-artifact');
const PLACEHOLDER = '/__CPBASE__';

rmSync(out, { recursive: true, force: true });
const r = spawnSync('npx', ['expo', 'export', '-p', 'web', '--clear', '--output-dir', out], {
  cwd: app,
  stdio: 'inherit',
  env: { ...process.env, CP_ARTIFACT: '1', EXPO_PUBLIC_HOSTING: 'artifact' },
  shell: process.platform === 'win32',
});
if (r.status !== 0) process.exit(r.status ?? 1);

const files = [];
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p);
    else files.push(p);
  }
})(out);

// 1) 번들 안의 자리표시자를 실행 시점 값으로
let replaced = 0;
const entries = [];
for (const f of files.filter((f) => f.endsWith('.js'))) {
  let js = readFileSync(f, 'utf8');
  // 앱 설정이 JSON 문자열로 들어간 곳은 식으로 바꿀 수 없으니 빈 값으로 둔다 (라우터는 이 값을 읽지 않음).
  js = js.split(`\\"baseUrl\\":\\"${PLACEHOLDER}\\"`).join('\\"baseUrl\\":\\"\\"');
  js = js.replace(/(?<!\\)"\/__CPBASE__([^"\\]*)"/g, (_m, rest) => {
    replaced++;
    return `(window.__CPBASE__+${JSON.stringify(rest)})`;
  });
  if (js.includes(PLACEHOLDER)) throw new Error(`바꾸지 못한 자리표시자가 남았어요: ${relative(out, f)}`);
  writeFileSync(f, js);
  // 아티팩트는 "_"로 시작하는 경로를 예약해 둬서, 번들을 js/ 로 옮긴다 (번들은 이 경로를 스스로 참조하지 않음).
  if (/[\\/]_expo[\\/]static[\\/]js[\\/]web[\\/]/.test(f)) {
    mkdirSync(join(out, 'js'), { recursive: true });
    const name = f.split(/[\\/]/).pop();
    renameSync(f, join(out, 'js', name));
    entries.push(`js/${name}`);
  }
}
rmSync(join(out, '_expo'), { recursive: true, force: true });

// 2) 페이지 본문 (아티팩트가 문서 뼈대를 덧씌운다)
const page = `<title>파트너스 매니저</title>
<style>
  /* 앱 화면 색은 앱 안에서 정하고, 여기서는 첫 화면이 그려지기 전 바탕만 맞춘다. */
  :root { --bg: #F4F6F8; --fg: #15191E; }
  @media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { --bg: #0E1114; --fg: #ECEFF2; color-scheme: dark; } }
  :root[data-theme="dark"] { --bg: #0E1114; --fg: #ECEFF2; color-scheme: dark; }
  html, body { height: 100%; }
  body { background: var(--bg); color: var(--fg); overflow: hidden; }
  #root { display: flex; height: 100%; flex: 1; }
</style>
<div id="root"></div>
<script>
  // 이 페이지가 열린 디렉터리를 앱의 기준 경로로 쓴다. 라우터가 첫 화면(홈)으로 시작하게 주소도 맞춘다.
  (function () {
    var dir = new URL('.', document.baseURI).pathname;
    window.__CPBASE__ = dir.replace(/\\/$/, '');
    if (location.pathname !== dir) {
      try { history.replaceState(null, '', dir + location.search + location.hash); } catch (e) {}
    }
  })();
</script>
${entries.map((e) => `<script src="${e}" defer></script>`).join('\n')}
`;
writeFileSync(join(out, 'index.html'), page);
rmSync(join(out, 'favicon.ico'), { force: true });
rmSync(join(out, 'metadata.json'), { force: true });

const listed = [];
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p);
    else listed.push([relative(out, p).split('\\').join('/'), statSync(p).size]);
  }
})(out);
const total = listed.reduce((a, [, s]) => a + s, 0);
console.log(`\n자리표시자 ${replaced}곳을 바꿨어요. 파일 ${listed.length}개, 합계 ${(total / 1024 / 1024).toFixed(2)}MB`);
for (const [p, s] of listed) console.log(`  ${p} (${(s / 1024).toFixed(0)}KB)`);
