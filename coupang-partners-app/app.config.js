// app.json 설정을 그대로 쓰고, Claude 아티팩트용 빌드(CP_ARTIFACT=1)일 때만 기준 경로를 자리표시자로 둔다.
// 아티팩트 화면은 어느 경로에서 열릴지 미리 알 수 없어서, scripts/build-artifact.mjs가 이 자리표시자를
// 페이지가 열린 실제 경로(window.__CPBASE__)로 바꿔 끼운다.
module.exports = ({ config }) => {
  if (process.env.CP_ARTIFACT !== '1') return config;
  return { ...config, experiments: { ...config.experiments, baseUrl: '/__CPBASE__' } };
};
