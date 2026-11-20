#!/usr/bin/env python3
"""호출어 채움말(fillers-ko.txt) 다시 만들기 — 개발용 (앱을 만들 때는 필요 없음).

Vosk 모델의 graph/Gr.fst 안에 든 낱말 표(OpenFst 기호 표)를 읽어 한글 한 글자 낱말만 뽑는다.
    python3 make_fillers.py <모델 폴더>/graph/Gr.fst > fillers-ko.txt
(모델: https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip, Apache-2.0)
"""
import re
import struct
import sys


def words(path):
    data = open(path, "rb").read(80_000_000)
    i = data.find(b"words.txt")
    if i < 0:
        raise SystemExit("낱말 표를 찾지 못했습니다 (Gr.fst 가 맞나요?)")
    i += len(b"words.txt")
    _avail, size = struct.unpack_from("<qq", data, i)
    i += 16
    for _ in range(size):
        n = struct.unpack_from("<i", data, i)[0]
        i += 4
        w = data[i:i + n].decode("utf-8", "replace")
        i += n + 8  # 낱말 + 번호(int64)
        yield w


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    one = [w for w in words(sys.argv[1]) if re.fullmatch(r"[가-힣]", w)]
    print("# '하이 자비스' 호출어 문법의 채움말: Vosk 한국어 small(vosk-model-small-ko-0.22) 어휘에 있는 한 글자 낱말 전부.")
    print("# 호출어가 아닌 말을 이 글자들로 풀게 해서 아무 말이나 호출어로 잡히지 않게 한다 (호출어의 글자는 앱이 빼고 쓴다).")
    print("# 만든 법: 모델 graph/Gr.fst 의 낱말 표에서 한글 한 글자만 (scripts/hotword/make_fillers.py).")
    print("\n".join(one))


if __name__ == "__main__":
    main()
