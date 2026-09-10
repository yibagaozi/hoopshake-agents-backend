package com.cnsportiot.cloud.harness.llm;

/**
 * 轻量 token 估算(与切分器同口径):CJK 字符 ≈ 1 token,其余 ≈ 1 token / 4 字符。
 * 仅用于预算裁剪的近似判断,不追求与模型分词精确一致(见 §5.2 T13)。
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0, other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                cjk++;
            } else if (!Character.isWhitespace(cp)) {
                other++;
            }
            i += Character.charCount(cp);
        }
        return cjk + (int) Math.ceil(other / 4.0);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)     // CJK 统一表意
                || (cp >= 0x3400 && cp <= 0x4DBF)  // 扩展 A
                || (cp >= 0x3000 && cp <= 0x303F)  // CJK 标点
                || (cp >= 0xFF00 && cp <= 0xFFEF); // 全角
    }
}
