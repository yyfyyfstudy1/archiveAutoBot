package com.ruoyi.config;

public class DocumentType {
    private String type;
    private String pattern;

    public DocumentType(String type, String pattern) {
        this.type = type;
        this.pattern = pattern;
    }

    public String getType() {
        return type;
    }

    public String getPattern() {
        return pattern;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DocumentType that = (DocumentType) o;
        return type.equals(that.type);
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }
}
