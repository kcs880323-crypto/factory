// 앱 아이콘 만들기 (url_app.py 가 부른다. 자바 표준 라이브러리만 씀).
//
//   java UrlAppIcon.java <res 폴더> <글자 후보들> <배경색 #RRGGBB> <모드> [원본 이미지]
//
//   글자 후보들: "가|G" 처럼 | 로 나눈 후보. 그릴 수 있는 첫 글자를 쓴다 (글꼴에 한글이 없을 때 대비).
//   모드: letter | image | maskable
//     image    : 일반 아이콘(애플 터치 아이콘, 파비콘 등). 불투명하면 가장자리 색을 배경으로 쓴다.
//     maskable : 웹 앱 매니페스트의 maskable 아이콘. 안드로이드 적응형 아이콘처럼 꽉 채워 그린다.
//
// 결과: res/mipmap-<밀도>/ic_launcher.png (안드로이드 7 용), ic_launcher_fg.png (8 이상 적응형 아이콘의 앞면)
// 표준 출력: {"icon_bg":"#RRGGBB","used":"image|letter"}
import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.List;

public class UrlAppIcon {
    static final String[] DENSITY = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    static final double[] SCALE = {1, 1.5, 2, 3, 4};
    static final List<String> FONT_PREFS = Arrays.asList(
            "Malgun Gothic", "Apple SD Gothic Neo", "Noto Sans CJK KR", "Noto Sans KR", "NanumGothic",
            "NanumBarunGothic", "WenQuanYi Zen Hei", "Segoe UI", "Helvetica Neue", "DejaVu Sans");

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (a.length < 4) {
            System.err.println("사용법: UrlAppIcon <res> <글자|후보> <#배경> <letter|image|maskable> [이미지]");
            System.exit(2);
        }
        File res = new File(a[0]);
        String[] letters = a[1].split("\\|");
        Color fallbackBg = Color.decode(a[2]);
        String mode = a[3];
        BufferedImage src = null;
        if (!mode.equals("letter") && a.length > 4) {
            try {
                src = ImageIO.read(new File(a[4]));
            } catch (Exception e) {
                src = null;
            }
            if (src != null && Math.min(src.getWidth(), src.getHeight()) < 64) src = null; // 너무 작으면 흐려서 글자 아이콘이 낫다
        }

        Color bg = fallbackBg;
        boolean opaque = false;
        boolean maskable = mode.equals("maskable");
        if (src != null) {
            // 꽉 찬 그림(사진·일러스트)이 정사각형이 아니면 가운데를 정사각형으로 잘라 쓴다. 투명한 로고는 여백을 붙인다.
            src = (src.getWidth() != src.getHeight() && isOpaque(src)) ? centerCrop(src) : toSquareArgb(src);
            opaque = isOpaque(src);
            if (opaque) bg = borderColor(src);
        }
        Font font = null;
        String letter = null;
        if (src == null) {
            for (String cand : letters) {
                if (cand.isEmpty()) continue;
                Font f = pickFont(cand);
                if (f != null) {
                    font = f;
                    letter = cand;
                    break;
                }
            }
            if (letter == null) {
                letter = "*";
                font = new Font(Font.SANS_SERIF, Font.BOLD, 100);
            }
        }

        for (int d = 0; d < DENSITY.length; d++) {
            File dir = new File(res, "mipmap-" + DENSITY[d]);
            dir.mkdirs();
            int legacy = (int) Math.round(48 * SCALE[d]);
            int fgSize = (int) Math.round(108 * SCALE[d]);
            ImageIO.write(drawLegacy(legacy, bg, src, opaque || maskable, font, letter), "png", new File(dir, "ic_launcher.png"));
            ImageIO.write(drawForeground(fgSize, bg, src, opaque, maskable, font, letter), "png", new File(dir, "ic_launcher_fg.png"));
        }
        System.out.println("{\"icon_bg\":\"" + hex(bg) + "\",\"used\":\"" + (src != null ? "image" : "letter") + "\"}");
    }

    // 안드로이드 7 용: 둥근 사각형 하나에 다 그린다.
    static BufferedImage drawLegacy(int s, Color bg, BufferedImage src, boolean fill, Font font, String letter) {
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hq(out);
        double pad = s * 0.04;
        RoundRectangle2D shape = new RoundRectangle2D.Double(pad, pad, s - 2 * pad, s - 2 * pad, s * 0.36, s * 0.36);
        g.setColor(bg);
        g.fill(shape);
        g.setClip(shape);
        if (src != null) {
            int inner = (int) Math.round(fill ? s - 2 * pad : (s - 2 * pad) * 0.72);
            int off = (s - inner) / 2;
            g.drawImage(scaled(src, inner), off, off, null);
        } else {
            drawLetter(g, s, bg, font, letter, 0.46);
        }
        g.dispose();
        return out;
    }

    // 안드로이드 8+ 적응형 아이콘의 앞면 (108dp 중 가운데 약 66~72dp 만 보인다).
    static BufferedImage drawForeground(int s, Color bg, BufferedImage src, boolean opaque, boolean maskable,
                                        Font font, String letter) {
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hq(out);
        if (src != null) {
            double ratio = maskable ? 1.0 : (opaque ? 0.70 : 0.52);
            int inner = (int) Math.round(s * ratio);
            int off = (s - inner) / 2;
            g.drawImage(scaled(src, inner), off, off, null);
        } else {
            drawLetter(g, s, bg, font, letter, 0.30);
        }
        g.dispose();
        return out;
    }

    static void drawLetter(Graphics2D g, int s, Color bg, Font base, String letter, double heightRatio) {
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(isLight(bg) ? new Color(0x20, 0x21, 0x24) : Color.WHITE);
        Font f = base.deriveFont(Font.BOLD, 100f);
        GlyphVector gv = f.createGlyphVector(g.getFontRenderContext(), letter);
        Rectangle2D b = gv.getVisualBounds();
        double target = s * heightRatio;
        double k = target / Math.max(b.getHeight(), b.getWidth() * 0.85);
        f = f.deriveFont((float) (100 * k));
        gv = f.createGlyphVector(g.getFontRenderContext(), letter);
        b = gv.getVisualBounds();
        float x = (float) ((s - b.getWidth()) / 2 - b.getX());
        float y = (float) ((s - b.getHeight()) / 2 - b.getY());
        g.drawGlyphVector(gv, x, y);
    }

    static Font pickFont(String letter) {
        String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        List<String> have = Arrays.asList(names);
        for (String pref : FONT_PREFS) {
            if (have.contains(pref)) {
                Font f = new Font(pref, Font.BOLD, 100);
                if (f.canDisplayUpTo(letter) == -1) return f;
            }
        }
        Font f = new Font(Font.SANS_SERIF, Font.BOLD, 100);
        if (f.canDisplayUpTo(letter) == -1 && !f.getFamily().equals("Dialog")) return f;
        for (String n : names) {
            Font any = new Font(n, Font.BOLD, 100);
            if (any.canDisplayUpTo(letter) == -1) return any;
        }
        return null;
    }

    static Graphics2D hq(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    // 크게 줄일 때 계단 현상이 없도록 절반씩 줄인다.
    static BufferedImage scaled(BufferedImage src, int size) {
        BufferedImage cur = src;
        int w = src.getWidth();
        while (w / 2 >= size) {
            w /= 2;
            cur = resize(cur, w);
        }
        return w == size ? cur : resize(cur, size);
    }

    static BufferedImage resize(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hq(out);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    static BufferedImage toSquareArgb(BufferedImage src) {
        int s = Math.max(src.getWidth(), src.getHeight());
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hq(out);
        g.drawImage(src, (s - src.getWidth()) / 2, (s - src.getHeight()) / 2, null);
        g.dispose();
        return out;
    }

    static BufferedImage centerCrop(BufferedImage src) {
        int s = Math.min(src.getWidth(), src.getHeight());
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hq(out);
        g.drawImage(src, -(src.getWidth() - s) / 2, -(src.getHeight() - s) / 2, null);
        g.dispose();
        return out;
    }

    // 테두리 픽셀이 거의 다 불투명하면 "꽉 찬" 아이콘으로 본다.
    static boolean isOpaque(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight(), total = 0, clear = 0;
        for (int x = 0; x < w; x++) {
            for (int p : new int[] {img.getRGB(x, 0), img.getRGB(x, h - 1)}) {
                total++;
                if ((p >>> 24) < 250) clear++;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int p : new int[] {img.getRGB(0, y), img.getRGB(w - 1, y)}) {
                total++;
                if ((p >>> 24) < 250) clear++;
            }
        }
        return clear <= total * 0.02;
    }

    static Color borderColor(BufferedImage img) {
        int s = img.getWidth(), band = Math.max(1, s / 25);
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                if (x >= band && x < s - band && y >= band && y < s - band) continue;
                int p = img.getRGB(x, y);
                r += (p >> 16) & 255;
                g += (p >> 8) & 255;
                b += p & 255;
                n++;
            }
        }
        return new Color((int) (r / n), (int) (g / n), (int) (b / n));
    }

    static boolean isLight(Color c) {
        return 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue() > 160;
    }

    static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
}
