// 자비스 제품 릴스 · 추천 페이지 · 품질 가드 개선 조치 이력.
// Antigravity 또는 Claude Desktop이 개선 조치를 완료·착수할 때마다 맨 위에 1건씩 추가한다.

export type ImprovementCategory = 'reels' | 'page' | 'guard' | 'experiment';
export type ImprovementStatus = 'done' | 'in_progress';

export interface ImprovementEntry {
  id: string;
  /** KST 기준 YYYY-MM-DD */
  date: string;
  category: ImprovementCategory;
  target: string;
  title: string;
  summary: string;
  status: ImprovementStatus;
  owner?: string;
}

export const IMPROVEMENTS: ImprovementEntry[] = [
  {
    id: 'IMP-20261124-05',
    date: '2026-11-24',
    category: 'experiment',
    target: '네이버 블로그 찰스 추천템 · 자비스 blog_rnd',
    title: 'B 라인(제품 블로그) R&D 자동 순환 가동 — 글별 클릭 실측·자동 승격',
    summary:
      '상품 글마다 챔피언·도전안을 번갈아 쓰고 쿠팡 링크를 글 전용 채널 ID(nb-…)로 발급해 글별 클릭을 실측해요. 판정 규칙(각 4편·7일, 1.2배 승격·0.9배 폐기)대로 대표 확인 없이 자동 적용하고, 표지에 제품 크게+큰 고민 글씨 배치(hero_keyword)를 더했어요. 네이버 조회수는 아직 데이터 필요.',
    status: 'in_progress',
    owner: '자비스',
  },
  {
    id: 'IMP-20261124-04',
    date: '2026-11-24',
    category: 'reels',
    target: '자비스 블로그 자동발행 · compose.ts',
    title: 'SEO 7단 구매 가이드 블로그 생성 로직 및 진실성 가드 연동',
    summary:
      '단순 5줄 링크 투척에서 문제 해결형 제목·3줄 요약·스펙 표·체크리스트·FAQ·연관 상품 클러스터 구조로 고도화하고, FIX-001 금지 표현 차단기 포함 자비스 연동 스크립트(generate_blog_post.py)와 앱 compose.ts를 반영했어요.',
    status: 'done',
    owner: 'Antigravity',
  },
  {
    id: 'IMP-20261124-03',
    date: '2026-11-24',
    category: 'reels',
    target: '자비스 영상일꾼 · R&D 모니터 앱',
    title: '바탕화면 실행용 영상일꾼 · R&D 모니터링 데스크톱 앱 구축',
    summary:
      '개선 조치 이력, R/K 라인 R&D 실험 현황, 로컬 영상일꾼 최근 MP4, 추천 페이지 이미지·중복 자동 점검, 회의 보드를 한 화면에서 보는 원클릭 데스크톱 앱(video-rnd/monitor-app/)을 구현했어요.',
    status: 'done',
    owner: 'Antigravity',
  },
  {
    id: 'IMP-20261124-02',
    date: '2026-11-24',
    category: 'page',
    target: '추천 페이지 28번 · 27번',
    title: '28번 브랜드 오매칭 설명 제거 및 27번 중복 정리',
    summary:
      '미트리 300g 닭가슴살 볶음밥에 잘못 붙은 타 브랜드(김단단 360g) 설명을 제거하고, 21번과 중복 등록된 27번 도마 채반 및 중복 이미지를 정리했어요.',
    status: 'done',
    owner: 'Antigravity',
  },
  {
    id: 'IMP-20261124-01',
    date: '2026-11-24',
    category: 'page',
    target: '추천 페이지 26번',
    title: '26번 봉지집게 누락 상품 이미지 연결 복구',
    summary: '이미지 없이 텍스트 카드로 노출되던 26번 상품에 상품 사진을 연결해 정상 표시되도록 조치했어요.',
    status: 'done',
    owner: '자비스 · Antigravity',
  },
  {
    id: 'FIX-001',
    date: '2026-11-24',
    category: 'guard',
    target: '제품 릴스 생성기 (R 라인)',
    title: '허위 별점·가격 불일치·체험 사칭 차단 검사기 구축',
    summary:
      '근거 없는 별점·1위·내돈내산 차단, 가격 4곳 일치, 플랫폼별 대가성 문구 분리, 미측정 품질점수 null 규칙을 설계·테스트했고 로컬 복사본에 적용 중이에요.',
    status: 'in_progress',
    owner: 'Claude Desktop · Antigravity',
  },
  {
    id: 'DEC-002',
    date: '2026-11-24',
    category: 'reels',
    target: '제품 릴스 R&D · 협업 보드',
    title: '1차 기준선(BASE-P1) 결함 진단 및 EXP-005 기준 확정',
    summary:
      '도마 채반 릴스 기준선의 가격·링크·초반 3초 약점을 진단하고, 0~3초 시연 훅(EXP-005) A/B 실험 기준과 공유 회의 보드(SYNC_BOARD.md)를 세팅했어요.',
    status: 'done',
    owner: 'Claude Desktop · Antigravity',
  },
];
