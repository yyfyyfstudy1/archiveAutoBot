package com.ruoyi.util;

import com.ruoyi.config.DocumentType;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DocumentTypeDetector {
    private static final Pattern TYPE_PATTERN = Pattern.compile("^(A|B|C):", Pattern.MULTILINE);

    public static DocumentType detect(String emailBody) {
        Matcher matcher = TYPE_PATTERN.matcher(emailBody);
        if (matcher.find()) {
            String type = matcher.group(1).toUpperCase();
            switch (type) {
                case "A":
                    return DocumentType.TYPE_A;
                case "B":
                    return DocumentType.TYPE_B;
                case "C":
                    return DocumentType.TYPE_C;
                default:
                    return null;
            }
        }
        return null; // 未知类型
    }
}
