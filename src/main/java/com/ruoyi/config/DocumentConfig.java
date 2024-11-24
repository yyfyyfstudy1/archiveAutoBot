package com.ruoyi.config;

public class DocumentConfig {
    private String templateBucketName;
    private String templateKey;
    private String prompt;

    public DocumentConfig(String templateBucketName, String templateKey, String prompt) {
        this.templateBucketName = templateBucketName;
        this.templateKey = templateKey;
        this.prompt = prompt;
    }

    public String getTemplateBucketName() {
        return templateBucketName;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public String getPrompt() {
        return prompt;
    }
}
