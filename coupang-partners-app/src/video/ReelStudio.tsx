// 휴대폰 앱: 영상 파일 만들기는 브라우저 기능(캔버스 녹화)이라 웹 화면에서만 된다.
import { Banner } from '../ui/kit';
import type { ReelPlan } from './reelDraw';

export interface ReelStudioProps {
  plan: ReelPlan;
  fileBase: string;
  imageUrl?: string;
  disabled?: boolean;
}

export function ReelStudio(_props: ReelStudioProps) {
  return (
    <Banner tone="info" icon="movie" title="영상 파일은 웹 화면에서 만들어요">
      휴대폰 앱에는 영상 녹화 기능을 넣지 않았어요. 같은 내용을 웹사이트나 Claude 화면에서 열면 상품 사진에 자막을 얹은 세로 영상(mp4/webm)을 바로 만들 수 있어요. 아래 대본·캡션은 여기서 복사해 CapCut 같은 편집 앱에서 써도 돼요.
    </Banner>
  );
}
