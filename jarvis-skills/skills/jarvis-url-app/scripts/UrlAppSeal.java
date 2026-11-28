// 앱 안 업데이트 파일 암호화 (--update-encrypt 로 만든 앱). 자바 표준 라이브러리만.
// 주소를 숨겨야 하는 앱(Tailscale·사내망 같은 사설 주소)의 APK 를 공개 저장소에 올려도 내용(주소 포함)을 볼 수 없게 한다.
// 열쇠(32바이트, base64 글자)는 이 컴퓨터(~/.jarvis-url-app/update-keys/)와 설치된 앱 안에만 있다.
// 모양: [12바이트 nonce][AES-256-GCM 암호문 + 16바이트 태그], AAD = 앱 id (다른 앱의 파일로 바꿔치기하면 열리지 않음).
//   java UrlAppSeal.java seal <열쇠파일> <앱 id> <입력> <출력>
//   java UrlAppSeal.java open <열쇠파일> <앱 id> <입력> <출력>
// 앱 쪽 풀기는 shell/UpdateInfo.java 의 open() (같은 방식).

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class UrlAppSeal {
    public static void main(String[] a) throws Exception {
        if (a.length != 5 || !(a[0].equals("seal") || a[0].equals("open"))) {
            System.err.println("사용법: java UrlAppSeal.java seal|open <열쇠파일> <앱 id> <입력> <출력>");
            System.exit(2);
        }
        byte[] key = Base64.getDecoder().decode(Files.readString(Path.of(a[1])).trim());
        if (key.length != 32) throw new IllegalArgumentException("열쇠는 32바이트여야 합니다");
        byte[] aad = a[2].getBytes(StandardCharsets.UTF_8);
        byte[] in = Files.readAllBytes(Path.of(a[3]));
        byte[] out;
        if (a[0].equals("seal")) {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            byte[] body = c.doFinal(in);
            out = new byte[12 + body.length];
            System.arraycopy(nonce, 0, out, 0, 12);
            System.arraycopy(body, 0, out, 12, body.length);
        } else {
            if (in.length < 12 + 16) throw new IllegalArgumentException("너무 짧습니다");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOf(in, 12)));
            c.updateAAD(aad);
            out = c.doFinal(in, 12, in.length - 12);
        }
        Files.write(Path.of(a[4]), out);
        System.out.println(out.length);
    }
}
