/*
 * PlayReporter 跨音源泄露探针 · JVM 侧复算（真实 long / float 语义）
 *
 * 为什么还要一个 Java 版：Python 的整数是无界的，符号要手工折叠；这里跑的是
 * **真正的 64 位有符号 long**（`1L << 62` 的实现），与 Kotlin 编译出的 JVM 字节码
 * 语义逐位一致。用于交叉验证 bit62 的十进制值与符号，以及 `toFloat()` 除法下的
 * `reachedCompletion` 边界。
 *
 * 这是**等价复算**，不是 app 的编译产物；每条判定都标注了源文件与行号。
 *
 * 运行： java GuardProbe.java      （JDK 11+ 单文件源码启动，实测 JDK 26）
 */
public class GuardProbe {

    /** SourceIds.kt:164  const val QQ_ID_FLAG: Long = 1L shl 62 */
    static final long QQ_ID_FLAG = 1L << 62;

    /** SourceIds.kt:167 */
    static boolean isQqId(long id) { return (id & QQ_ID_FLAG) != 0L; }

    /** SourceIds.kt:209 */
    static Long qqRawId(long id) { return isQqId(id) ? (id & (QQ_ID_FLAG - 1L)) : null; }

    /** SourceIds.kt:188 */
    static String sourceOfId(long id) { return isQqId(id) ? "QQMUSIC" : "NETEASE"; }

    /** PlayReporter.kt:82  durationMs > 0 && positionMs.toFloat() / durationMs.toFloat() >= 0.8f */
    static boolean reachedCompletion(long positionMs, long durationMs) {
        return durationMs > 0 && positionMs / (float) durationMs >= 0.8f;
    }

    /** PlayReporter.kt:47-51 的入口卫语句。返回 null = 通过。 */
    static String reportPlayGuard(long songId, boolean hasCookie, boolean cookieHasMusicU, boolean hasCsrf) {
        if (!hasCookie) return "L47 getCookie() == null";
        if (!cookieHasMusicU || songId <= 0L) return "L48 !contains(MUSIC_U) || songId <= 0";
        if (!hasCsrf) return "L50 getCsrfToken() == null";
        return null;
    }

    public static void main(String[] args) {
        System.out.println("QQ_ID_FLAG(1L shl 62) = " + QQ_ID_FLAG);
        System.out.println("Long.MAX_VALUE        = " + Long.MAX_VALUE);
        System.out.println("Long.signum(QQ_ID_FLAG) = " + Long.signum(QQ_ID_FLAG));

        long[][] cases = {
            {4611686018784987997L, 0},   // 真机实测：QQ《怪我太天真》/ 苏谭谭
            {357600093L,            0},  // 上面那首反解出的裸 songid
            {557920L,               0},  // 网易云样本（S6 落盘）
            {186016L,               0},  // 网易云《晴天》
            {QQ_ID_FLAG,            0},  // 只有标志位
            {QQ_ID_FLAG + 1L,       0},
            {QQ_ID_FLAG + 1000000000L, 0},
        };
        String[] labels = {
            "真机 QQ《怪我太天真》", "  其裸 songid", "网易云 S6 样本", "网易云《晴天》",
            "只有 bit62", "bit62+1", "bit62+1e9",
        };

        System.out.println();
        System.out.printf("%-22s %21s %5s %6s %9s %-8s %14s%n",
            "样本", "十进制", "符号", ">0", "isQqId", "source", "qqRawId");
        System.out.println("-".repeat(92));
        for (int i = 0; i < cases.length; i++) {
            long id = cases[i][0];
            Long raw = qqRawId(id);
            System.out.printf("%-22s %21d %5s %6s %9s %-8s %14s%n",
                labels[i], id, Long.signum(id) < 0 ? "-" : "+", (id > 0), isQqId(id),
                sourceOfId(id), raw == null ? "null" : raw.toString());
        }

        System.out.println();
        System.out.println("入口卫语句逐条求值（cookie 含 MUSIC_U 与 __csrf，即网易云已登录）：");
        for (int i = 0; i < cases.length; i++) {
            long id = cases[i][0];
            String blocked = reportPlayGuard(id, true, true, true);
            System.out.printf("  %-22s id=%21d -> %s%n", labels[i], id,
                blocked == null ? "PASS：会发出上报" : ("BLOCK：" + blocked));
        }

        System.out.println();
        System.out.println("reachedCompletion（float 除法，阈值 0.8f）：");
        long[][] rc = {{0,0},{0,200000},{159999,200000},{160000,200000},{200000,200000},{1,3}};
        for (long[] p : rc) {
            System.out.printf("  reachedCompletion(pos=%7d, dur=%7d) = %s%n", p[0], p[1], reachedCompletion(p[0], p[1]));
        }
    }
}
