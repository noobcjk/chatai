package com.ai.client.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Markdown 解析：只识别 ``` 围栏代码块，把一条消息切成「正文段」与「代码段」。
 *
 * <p>围栏没有闭合时（流式回复进行中很常见）把剩余内容整体当作代码段，
 * 这样 ``` 一出现就立刻按代码渲染，不用等收尾才切换。</p>
 */
public final class MarkdownParser {

    public enum Kind {
        TEXT,
        CODE
    }

    /**
     * 一段内容。
     *
     * @param kind     TEXT 为普通正文，CODE 为代码
     * @param language 代码段围栏后标注的语言（可为空）
     * @param text     段落文本，代码段不含围栏行
     */
    public record Segment(Kind kind, String language, String text) {
    }

    private MarkdownParser() {
    }

    public static List<Segment> parse(String markdown) {
        List<Segment> segments = new ArrayList<>();
        if (markdown == null) {
            return segments;
        }

        List<String> textLines = new ArrayList<>();
        List<String> codeLines = new ArrayList<>();
        boolean inCode = false;
        String language = "";

        for (String rawLine : markdown.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.trim().startsWith("```")) {
                if (inCode) {
                    segments.add(new Segment(Kind.CODE, language, String.join("\n", codeLines)));
                    codeLines.clear();
                    inCode = false;
                } else {
                    if (!textLines.isEmpty()) {
                        segments.add(new Segment(Kind.TEXT, "", String.join("\n", textLines)));
                        textLines.clear();
                    }
                    language = line.trim().substring(3).trim();
                    inCode = true;
                }
                continue;
            }
            (inCode ? codeLines : textLines).add(line);
        }

        if (inCode) {
            segments.add(new Segment(Kind.CODE, language, String.join("\n", codeLines)));
        } else if (!textLines.isEmpty()) {
            segments.add(new Segment(Kind.TEXT, "", String.join("\n", textLines)));
        }
        if (segments.isEmpty()) {
            segments.add(new Segment(Kind.TEXT, "", ""));
        }
        return segments;
    }
}
