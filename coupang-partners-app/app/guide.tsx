import { router } from 'expo-router';
import { View } from 'react-native';

import { DEFAULT_DISCLOSURE } from '@/src/lib/compose';
import { Banner, Button, Card, Icon, Row, Screen, T, type IconName } from '@/src/ui/kit';
import { PARTNERS_SITE } from '@/src/ui/parts';
import { useTheme } from '@/src/ui/theme';

function Step({ n, title, icon, children }: { n: number; title: string; icon: IconName; children: React.ReactNode }) {
  const t = useTheme();
  return (
    <Card>
      <Row gap={10}>
        <View style={{ width: 30, height: 30, borderRadius: 15, backgroundColor: t.primarySoft, alignItems: 'center', justifyContent: 'center' }}>
          <T v="h3" color="primary">
            {String(n)}
          </T>
        </View>
        <T v="h2" style={{ flex: 1 }}>
          {title}
        </T>
        <Icon name={icon} color={t.faint} />
      </Row>
      {children}
    </Card>
  );
}

function Li({ children }: { children: React.ReactNode }) {
  return (
    <Row gap={8} style={{ alignItems: 'flex-start' }}>
      <T v="sub">•</T>
      <T v="sub" style={{ flex: 1 }}>
        {children}
      </T>
    </Row>
  );
}

export default function Guide() {
  return (
    <Screen>
      <View style={{ gap: 4 }}>
        <T v="title">쿠팡 파트너스, 이렇게 시작해요</T>
        <T v="sub">내 링크를 보고 누군가 쿠팡에서 사면, 판매액의 일부를 수수료로 받는 제휴 마케팅이에요.</T>
      </View>
      <Banner tone="info" icon="info">
        아래 내용은 공개된 안내와 자료를 정리한 것이에요. 승인 조건, 수수료율, 정산 일정, 금지 행위는 바뀔 수 있으니 파트너스 사이트의 공지와 약관을 꼭 함께 확인하세요.
      </Banner>

      <Step n={1} title="가입하기" icon="person">
        <Li>파트너스 사이트에 쿠팡 계정으로 로그인해 가입을 신청해요.</Li>
        <Li>활동할 채널(블로그, 인스타그램, 유튜브 등) 주소와 소개를 적어요. 실제로 운영 중인 채널일수록 좋아요.</Li>
        <Li>수익을 받을 지급 정보(본인 인증, 계좌 등)는 사이트 안내에 따라 등록해요.</Li>
        <Button label="파트너스 사이트 열기" icon="open" kind="secondary" href={PARTNERS_SITE} />
      </Step>

      <Step n={2} title="첫 링크 만들기 (API 없이)" icon="link">
        <Li>파트너스 사이트의 링크 만들기 메뉴에서 상품을 검색하거나 쿠팡 상품 주소를 넣어요.</Li>
        <Li>만들어진 짧은 링크(link.coupang.com/a/…)를 복사해요.</Li>
        <Li>이 앱의 링크 탭에서 &lsquo;붙여넣기&rsquo; → 상품 이름을 적고 저장해요.</Li>
        <Li>일반 쿠팡 주소(coupang.com/vp/products/…)를 그대로 올리면 수익이 잡히지 않아요. 앱이 경고해 줘요.</Li>
        <Button label="링크 탭으로" icon="link" kind="secondary" onPress={() => router.push('/links')} />
      </Step>

      <Step n={3} title="홍보 글 올리기" icon="megaphone">
        <Li>홈 또는 링크 카드에서 &lsquo;글 만들기&rsquo;를 누르고 올릴 곳(블로그·인스타·카톡·스레드)을 골라요.</Li>
        <Li>대가성 문구는 글의 제목이나 첫 부분에 보여야 해요 (공정위 추천·보증 심사지침, 2024년 12월 개정). 앱이 맨 앞에 넣고, 빠지면 복사를 막아요.</Li>
        <Li>기본 문구: &ldquo;{DEFAULT_DISCLOSURE}&rdquo;</Li>
        <Li>인스타그램 캡션의 링크는 눌리지 않아서 프로필 링크나 스토리 링크를 함께 써요.</Li>
      </Step>

      <Step n={4} title="최종 승인과 API 키" icon="key">
        <Li>가입 직후에는 활동을 지켜보는 단계이고, 일정 실적을 채우면 최종 승인 심사를 받는다고 안내돼 있어요. 기준은 자료마다 &lsquo;누적 판매액 15만 원 이상&rsquo;으로 알려져 있지만, 정확한 조건은 사이트 공지를 확인하세요.</Li>
        <Li>API 키(Access Key, Secret Key)는 파트너스 사이트의 파트너스 API 메뉴에서 받아요.</Li>
        <Li>키를 설정 탭에 넣으면 상품 검색, 골드박스·카테고리 베스트, 자동 링크 변환, 실적 조회가 열려요.</Li>
        <Li>키는 비밀번호처럼 다뤄요. 남에게 보내거나 글·스크린샷에 노출하지 마세요.</Li>
        <Button label="설정 탭에서 키 넣기" icon="key" kind="secondary" onPress={() => router.push('/settings')} />
      </Step>

      <Step n={5} title="하지 말아야 할 것" icon="warn">
        <Li>내 링크로 나·가족이 사서 수익 만들기</Li>
        <Li>클릭·구매를 대가로 포인트나 선물을 주는 &lsquo;리워드&rsquo;형 홍보</Li>
        <Li>같은 링크를 카페·댓글·단톡방에 도배하는 스팸성 홍보</Li>
        <Li>쿠팡 공식 계정·공식 이벤트처럼 보이게 꾸미기</Li>
        <Li>링크를 숨기거나, 누르지 않았는데 쿠팡으로 강제로 이동시키기</Li>
        <Li>대가성 문구를 빼거나 눈에 안 띄게 두기, 써 보지 않은 후기를 지어내기</Li>
        <Li>API를 짧은 시간에 너무 많이 부르기 (계정 정지 사유로 알려져 있어요 — 앱이 시간당 호출 수를 제한해요)</Li>
        <T v="caption">이런 행위는 수익 취소나 활동 정지로 이어질 수 있어요. 정확한 목록은 약관과 운영 정책을 확인하세요.</T>
      </Step>

      <Step n={6} title="자비스와 함께 쓰기" icon="bolt">
        <Li>링크 탭의 &lsquo;자비스로 보내기&rsquo;를 누르면 보관함의 파트너스 링크가 자비스 형식으로 복사돼요.</Li>
        <Li>자비스 🛒 → 🔗 내 제휴 상품 → 📥 파트너스 매니저에서 가져오기에 붙여 넣어요. 예시 링크·일반 쿠팡 주소는 들어가지 않고, 같은 링크는 두 번 들어가지 않아요.</Li>
        <Li>자비스에서 상품마다 🌐 찰스의 추천템에 올리기·빼기, 제품 릴스, 상품 소개 블로그 글을 만들어요. 공개되는 일은 자비스가 확인을 받은 뒤에만 해요.</Li>
        <Li>쿠팡 파트너스에 등록한 내 채널(웹사이트·블로그)은 설정 → 내 채널에 적어 두면 홈에서 바로 열 수 있어요.</Li>
      </Step>

      <Step n={7} title="실적 보고 키우기" icon="chart">
        <Li>실적은 매일 오후 3시쯤 전날 것까지 반영돼요. 수익은 취소·반품에 따라 바뀌는 확정 전 금액이에요.</Li>
        <Li>채널마다 다른 채널 ID(subId)를 쓰면 어느 곳에서 잘 팔리는지 나눠 볼 수 있어요.</Li>
        <Li>잘 팔린 상품은 다른 채널이나 다른 각도의 글로 한 번 더 소개해요.</Li>
        <Li>골드박스(매일 오전 7시 30분쯤 바뀌는 특가)와 계절 키워드를 활용해요.</Li>
        <Li>정산 일정, 최소 지급 금액, 세금 처리는 파트너스 사이트의 정산 안내가 기준이에요.</Li>
      </Step>
    </Screen>
  );
}
