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
    id: 'IMP-20261127-02',
    date: '2026-11-27',
    category: 'reels',
    target: '본편 자막 리듬·상품 선정 중복 방지 (NIGHT-014)',
    title: '자막 한 줄 14자·핵심어 1개 강조 + 최근 3건 유사 품목 연속 차단',
    summary:
      '무음 시청에서도 한눈에 읽히도록 본편 자막을 모든 줄 14자 이하로 줄바꿈하고 한 화면에 핵심어 1개만 강조 색을 쓰도록 다듬었어요. 상품 자동 선정에서는 최근 3건의 핵심 품목명을 비교해 결로쿠션 같은 유사 상품이 연속으로 뽑히지 않도록 막았어요.',
    status: 'done',
    owner: 'Antigravity(임시 대행)',
  },
  {
    id: 'IMP-20261127-01',
    date: '2026-11-27',
    category: 'experiment',
    target: '첫 3초 시연 훅 (NIGHT-013 / EXP-005)',
    title: '첫 프레임 0초 작동 시연 + 상단 훅 14자 안전영역 고정',
    summary:
      '상단 훅 글자를 14자 이하로 줄이고 인스타 상단 안전영역(제품 중앙 비가림)에 고정해 첫 화면에서 제품이 가려지지 않게 했어요. Flow 오프닝 영상도 0초부터 제품이 크게 움직이는 시연 장면을 우선 만들도록 바꿨어요.',
    status: 'done',
    owner: 'Antigravity(임시 대행)',
  },
  {
    id: 'IMP-20261125-03',
    date: '2026-11-25',
    category: 'guard',
    target: '영상 작업 링크·상품 선정 (NIGHT-011)',
    title: '추천 페이지에 올린 링크로 영상 만들기',
    summary:
      '아침 세탁조클리너 영상이 「제휴 링크 없음」으로 멈췄어요. 생성기가 자비스가 가진 진짜 링크를 링크 발급에 실패한 수집 결과로 덮어썼기 때문이에요. 이제 영상 작업에는 추천 페이지에 올린 쿠팡 링크가 그대로 들어가고 덮이지 않아요. 30개 묶음처럼 대량 묶음 상품은 1묶음 가격으로 점수를 매겨 고가 꿀템으로 뽑히지 않게 했어요.',
    status: 'done',
    owner: '자비스',
  },
  {
    id: 'IMP-20261125-02',
    date: '2026-11-25',
    category: 'reels',
    target: 'Flow 전 대본 채점 (NIGHT-010)',
    title: '대본 점수 개선 — 채점 원칙 정렬·고쳐 쓰기 sonnet',
    summary:
      '채점기가 과장 표현이 없다고 감점하던 것을 바로잡고, 훅 채점에도 확인된 가격을 알려 줘요. 고쳐 쓰기만 더 좋은 모델로 바꿨어요. 세탁조클리너는 4번째에 대본 81·훅 87점으로 통과했어요.',
    status: 'done',
    owner: '자비스',
  },
  {
    id: 'IMP-20261125-01',
    date: '2026-11-25',
    category: 'guard',
    target: '대본 관문·업로드 재확인 (NIGHT-009)',
    title: '무료 채점일 때만 통과 기준 80점',
    summary:
      'API 키 잔액이 없어 Claude Code(무료)로 채점하면 점수가 10점쯤 짜게 나와요. 그래서 그 점수를 낸 채점 모델이 무료일 때만 대본·훅 관문과 업로드 직전 재확인 기준을 80점으로, API 채점이면 그대로 90점으로 둬요. 로그에 적용 기준을 함께 적고, API 키를 충전하면 자동으로 90점으로 돌아가요.',
    status: 'done',
    owner: '자비스',
  },
  {
    id: 'IMP-20261124-13',
    date: '2026-11-24',
    category: 'guard',
    target: '제품 릴스 링크·대본·발행 (NIGHT-007)',
    title: '오트밀 영상에 땅콩버터가 소개된 사고 재발 방지',
    summary:
      '판매 링크가 영상 상품과 같은지 수집·자동화·추천 페이지에서 확인하고, Flow 전에 대본을 채점·고쳐 써서 실채점 90점·위반 0건일 때만 장면을 만들어요. 일꾼이 실패로 알린 영상은 올리지 않아요. 추천 페이지 34번(골든잇츠) 삭제, 33번 플라하반 확인.',
    status: 'done',
    owner: '자비스',
  },
  {
    id: 'IMP-20261124-12',
    date: '2026-11-24',
    category: 'reels',
    target: '대본 가격 (NIGHT-003)',
    title: '대사 가격을 쿠팡 확인가로',
    summary:
      '일꾼에게 보내는 작업의 가격이 확인가의 절반으로 들어가 「이만원대」(실제 40,500원)라고 말하던 것을 고쳤어요. 과장 템플릿 문구도 대본 채점에서 막아요.',
    status: 'done',
    owner: '자비스',
  },
  {
    id: 'IMP-20261124-11',
    date: '2026-11-24',
    category: 'guard',
    target: '원본 생성기 채점 (NIGHT-005)',
    title: 'API 키 없이도 실제 채점',
    summary:
      '일꾼에서 검증된 Claude Code CLI 채점을 원본에 반영하고, 키가 있어도 API가 실패하면 CLI로 채점해요(기본 98.5점만 남던 문제 방지).',
    status: 'done',
    owner: '자비스',
  },
  {
    id: 'IMP-20261124-10',
    date: '2026-11-24',
    category: 'reels',
    target: '상품 선정 (NIGHT-004)',
    title: '살림꿀템 위주 + 가격 높은 상품 우선',
    summary:
      '하루 비중 살림꿀템 60%·시즌가전 25%·식품 15%, 식품 연속 금지, 3만~15만원 우선. 실데이터 dry-run 평균 확인가 171,752원.',
    status: 'done',
    owner: '자비스',
  },
  {
    id: 'IMP-20261124-09',
    date: '2026-11-24',
    category: 'reels',
    target: '제품 릴스 품질 (NIGHT-006)',
    title: '댓글 키워드·첫 화면 글자 위치·채점 정상화',
    summary:
      '댓글 키워드에서 「드럼·혼합·30개」를 빼고 핵심 품목(오트밀·볶음밥)으로, 첫 화면 글자를 위로 올려 제품을 가리지 않게, 채점기의 과장 문구 가산점을 없앴어요.',
    status: 'done',
    owner: '자비스',
  },
  {
    id: 'IMP-20261124-08',
    date: '2026-11-24',
    category: 'page',
    target: '추천 페이지 등록기 (NIGHT-001)',
    title: '누락·중복·오매칭 등록 차단',
    summary:
      '사진 파일이 실제로 있는지, 같은 상품·같은 사진이 이미 있는지, 자동 설명이 다른 상품 이야기인지 등록 전에 확인해요.',
    status: 'done',
    owner: '자비스',
  },
  {
    id: 'IMP-20261124-07',
    date: '2026-11-24',
    category: 'guard',
    target: '블로그 자동발행 (NIGHT-002)',
    title: 'SEO 7단 구매 가이드 + 진실성 검사',
    summary:
      '상품 글마다 7단 구매 가이드를 함께 만들고, 상품 소개 글은 발행 직전에 체험 사칭·근거 없는 1위·최저가·출처 없는 별점이 있으면 발행하지 않아요.',
    status: 'done',
    owner: '자비스',
  },
  {
    id: 'IMP-20261124-06',
    date: '2026-11-24',
    category: 'reels',
    target: '자비스 야간 자동 개발 (03:30~05:00)',
    title: 'R&D 개선 과제 새벽 자동 개발·반영 및 실적 탭 자동 표기 체계 구축',
    summary:
      '낮 동안 쌓인 R&D 개선 과제(NIGHTLY_QUEUE.md)를 매일 새벽 03:30 자비스 Claude가 자동 백업·개발·테스트 후 반영하고, 완료 내역을 제품 릴스 실적 탭(improvements.ts)과 UPDATES.md에 자동 기록하도록 구축했어요.',
    status: 'done',
    owner: 'Antigravity',
  },
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
