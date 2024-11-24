package com.ruoyi.config;

public class DocumentConfig {
    private String templateBucketName;
    private String templateKey;
    private String prompt;
    private String googleFolderId;
    private String fileName;


    public DocumentConfig(String templateBucketName, String templateKey, String prompt, String googleFolderId, String fileName) {
        this.templateBucketName = templateBucketName;
        this.templateKey = templateKey;
        this.prompt = prompt;
        this.googleFolderId = googleFolderId;
        this.fileName = fileName;
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

    public String getGoogleFolderId() {
        return googleFolderId;
    }

    public String getFileName() {
        return fileName;
    }
}
