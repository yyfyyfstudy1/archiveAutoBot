package com.ruoyi.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class ConfigLoader {
    private final SsmClient ssmClient;
    private final String parameterName;
    private Map<DocumentType, DocumentConfig> configMap;

    public ConfigLoader(SsmClient ssmClient, String parameterName) throws IOException {
        this.ssmClient = ssmClient;
        this.parameterName = parameterName;
        this.configMap = new HashMap<>();
        loadConfig();
    }

    private void loadConfig() throws IOException {
        GetParameterRequest getParameterRequest = GetParameterRequest.builder()
                .name(parameterName)
                .withDecryption(false)
                .build();
        GetParameterResponse getParameterResponse = ssmClient.getParameter(getParameterRequest);
        String configJson = getParameterResponse.parameter().value();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(configJson);
        JsonNode documentTypes = root.get("documentTypes");
        if (documentTypes != null) {
            Iterator<Map.Entry<String, JsonNode>> fields = documentTypes.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String typeStr = entry.getKey();
                DocumentType type = DocumentType.valueOf(typeStr.toUpperCase());
                JsonNode configNode = entry.getValue();
                String templateBucket = configNode.get("templateBucket").asText();
                String templateKey = configNode.get("templateKey").asText();
                String prompt = configNode.get("prompt").asText();
                String googleFolderId = configNode.get("googleFolderId").asText();
                String fileName = configNode.get("fileName").asText();

                DocumentConfig config = new DocumentConfig(templateBucket, templateKey, prompt, googleFolderId, fileName);
                configMap.put(type, config);
            }
        }
    }

    public DocumentConfig getConfig(DocumentType type) {
        return configMap.get(type);
    }
}
