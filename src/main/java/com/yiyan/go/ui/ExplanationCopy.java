package com.yiyan.go.ui;

/** Display-only cleanup for records written by older versions. The archived original is untouched. */
final class ExplanationCopy {
    private ExplanationCopy() { }

    static String forReplay(String text, boolean pass) {
        if (pass && text.contains("浅层检查未找到既安全又有明确作用的落点")) return "暂不落子，等待对方应手。";
        if (pass && text.contains("我同意进入死子确认与数子")) return "同意停手，进入结算。";
        return text.replace("以上核验不代表已判断死活或胜负。", "")
                .replace("这不代表已判定死活或胜负。", "")
                .replace("尚未据此判断谁领先。", "")
                .replace("说明仅供参考，不代表专业引擎判断。", "").trim();
    }
}
