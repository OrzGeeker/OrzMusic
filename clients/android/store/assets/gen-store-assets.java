/*
 * 生成客户端商店物料里「带文字」的那两张图:
 *   · store/assets/feature-graphic-1024x500.png   (Google Play 特色图片)
 *   · store/assets/banner-320x180.png             (Android TV 启动器 banner,
 *                                                  同步到 app/src/main/res/drawable/banner.png)
 *
 * **图标的唯一来源是服务端仓库的品牌制品**,不看本文件:
 *   Resources/Public/brand/orz-logo.svg      可编辑母版
 *   Resources/Public/brand/orz-logo-512.png  商店 512 图标(由脚本直接拷贝)
 * 统一入口是 clients/android/scripts/sync-brand-assets.sh,别手工改 store/assets/icon-512.png。
 *
 * 本文件里的几何数值**抄自 orz-logo.svg**(64×64 画布):
 *   rect 64×64 rx=12 #0B0D0C / circle r=25 stroke-width=8 #F4F2EA / 五根薄荷柱
 * 改品牌时两边一起改,并在 PR 里说明。
 *
 * 为什么用 AWT 而不是 ImageMagick:JDK 是构建必备,不引入新依赖。
 *
 * 用法(仓库根):
 *   java -Djava.awt.headless=true clients/android/store/assets/gen-store-assets.java \
 *        clients/android/store/assets \
 *        clients/android/app/src/main/res/drawable/banner.png
 */
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

public class GenStoreAssets {

    static final Color BG_TOP = new Color(0x0E1411);
    static final Color BG_BOTTOM = new Color(0x070908);
    static final Color BRAND_BG = new Color(0x0B0D0C);
    static final Color MINT = new Color(0x39E58C);
    static final Color TEXT = new Color(0xF4F2EA);
    static final Color MUTED = new Color(0x89918B);

    // 品牌图形(单位见 orz-logo.svg 的 64×64 画布)
    static final double LOGO_UNITS = 64.0;
    static final double RING_R = 25.0;
    static final double RING_W = 8.0;
    static final double BG_RX = 12.0;
    // 五根柱:x / y / 宽 / 高
    static final double[][] BARS = {
            {16, 28, 4, 8},
            {23, 24, 4, 16},
            {30, 20, 4, 24},
            {37, 24, 4, 16},
            {44, 28, 4, 8},
    };

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : ".");
        File bannerTarget = args.length > 1 ? new File(args[1]) : null;
        outDir.mkdirs();

        BufferedImage banner = banner(320, 180);
        write(banner, new File(outDir, "banner-320x180.png"));
        write(featureGraphic(1024, 500), new File(outDir, "feature-graphic-1024x500.png"));
        if (bannerTarget != null) {
            bannerTarget.getParentFile().mkdirs();
            write(banner, bannerTarget);
            System.out.println("wrote " + bannerTarget);
        }
        System.out.println("wrote " + new File(outDir, "banner-320x180.png"));
        System.out.println("wrote " + new File(outDir, "feature-graphic-1024x500.png"));
        System.out.println("(icon-512.png 由 scripts/sync-brand-assets.sh 从品牌制品拷贝)");
    }

    static void write(BufferedImage img, File f) throws Exception {
        ImageIO.write(img, "png", f);
    }

    static BufferedImage canvas(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    static Graphics2D g2(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** 深色纵向渐变 + 左上一团薄荷微光,与应用背景同气质。 */
    static void paintBackground(Graphics2D g, int w, int h) {
        g.setPaint(new GradientPaint(0, 0, BG_TOP, 0, h, BG_BOTTOM));
        g.fillRect(0, 0, w, h);
        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(w * 0.16f, h * 0.10f),
                Math.max(w, h) * 0.7f,
                new float[]{0f, 1f},
                new Color[]{new Color(0x39, 0xE5, 0x8C, 44), new Color(0x39, 0xE5, 0x8C, 0)}));
        g.fillRect(0, 0, w, h);
    }

    /** 按 orz-logo.svg 的比例画品牌图形:暖白圆环 + 薄荷五柱。 */
    static void drawLogo(Graphics2D g, double left, double top, double size) {
        double k = size / LOGO_UNITS;
        double cx = left + size / 2.0;
        double cy = top + size / 2.0;

        g.setColor(TEXT);
        g.setStroke(new BasicStroke((float) (RING_W * k), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        double r = RING_R * k;
        g.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));

        g.setColor(MINT);
        for (double[] b : BARS) {
            double x = left + b[0] * k;
            double y = top + b[1] * k;
            double w = b[2] * k;
            double h = b[3] * k;
            double rr = (b[2] / 2.0) * k;
            g.fill(new RoundRectangle2D.Double(x, y, w, h, rr, rr));
        }
    }

    /** 整块图标(圆角深底 + 品牌图形)。仅用于快速预览/兜底,商店图以品牌 PNG 为准。 */
    static BufferedImage icon(int size) {
        BufferedImage img = canvas(size, size);
        Graphics2D g = g2(img);
        double rx = BG_RX / LOGO_UNITS * size;
        g.setColor(BRAND_BG);
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, rx, rx));
        drawLogo(g, 0, 0, size);
        g.dispose();
        return img;
    }

    static Font font(int style, float size) {
        // WSL / CI 里只有 DejaVu 系;逻辑字体 SansSerif 会回落到它,不会缺字。
        Font f = new Font("DejaVu Sans", style, 12);
        if (!"DejaVu Sans".equals(f.getFamily())) f = new Font(Font.SANS_SERIF, style, 12);
        return f.deriveFont(style, size);
    }

    /** 按可用宽度收缩字号 —— 不同机器的字体度量不一样,写字板不能假设它放得下。 */
    static Font fitFont(Graphics2D g, String text, int style, float want, double maxWidth) {
        Font f = font(style, want);
        g.setFont(f);
        double w = g.getFontMetrics().stringWidth(text);
        if (w > maxWidth && w > 0) {
            f = font(style, (float) (want * maxWidth / w));
            g.setFont(f);
        }
        return f;
    }

    static BufferedImage featureGraphic(int w, int h) {
        BufferedImage img = canvas(w, h);
        Graphics2D g = g2(img);
        paintBackground(g, w, h);

        double logo = h * 0.62;
        double left = w * 0.075;
        drawLogo(g, left, (h - logo) / 2.0, logo);

        double textX = left + logo + w * 0.045;
        int titleBaseline = (int) (h * 0.53);
        fitFont(g, "OrzMusic", Font.BOLD, (float) (h * 0.175), w - textX - w * 0.06);
        g.setColor(TEXT);
        g.drawString("OrzMusic", (float) textX, titleBaseline);

        fitFont(g, "CHIP AUDIO · MOD · XM · IT · S3M", Font.PLAIN, (float) (h * 0.048),
                w - textX - w * 0.06);
        g.setColor(MUTED);
        g.drawString("CHIP AUDIO · MOD · XM · IT · S3M", (float) textX, (float) (titleBaseline + h * 0.10));

        g.dispose();
        return img;
    }

    static BufferedImage banner(int w, int h) {
        BufferedImage img = canvas(w, h);
        Graphics2D g = g2(img);
        paintBackground(g, w, h);

        double logo = h * 0.74;
        double left = w * 0.06;
        drawLogo(g, left, (h - logo) / 2.0, logo);

        double textX = left + logo + w * 0.05;
        fitFont(g, "OrzMusic", Font.BOLD, (float) (h * 0.24), w - textX - w * 0.05);
        g.setColor(TEXT);
        g.drawString("OrzMusic", (float) textX, (float) (h * 0.56));
        fitFont(g, "CHIP AUDIO", Font.PLAIN, (float) (h * 0.08), w - textX - w * 0.05);
        g.setColor(MUTED);
        g.drawString("CHIP AUDIO", (float) textX, (float) (h * 0.74));

        g.dispose();
        return img;
    }
}
