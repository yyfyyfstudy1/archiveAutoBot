package com.ruoyi.util;

import com.ruoyi.config.DocumentType;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class DocumentTypeDetector {
    
    public static DocumentType detect(String emailBody, List<DocumentType> documentTypes) {
        if (emailBody == null || emailBody.isEmpty() || documentTypes == null || documentTypes.isEmpty()) {
            return null;
        }

        for (DocumentType docType : documentTypes) {
            Pattern pattern = Pattern.compile(docType.getPattern(), Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(emailBody);
            if (matcher.find()) {
                return docType;
            }
        }
        
        return null;
    }
}
