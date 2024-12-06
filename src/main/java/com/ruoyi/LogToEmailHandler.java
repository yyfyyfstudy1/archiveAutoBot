package com.ruoyi;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.CloudWatchLogsEvent;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simpleemail.model.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

public class LogToEmailHandler implements RequestHandler<CloudWatchLogsEvent, String> {

    // 配置您的发件人和收件人邮箱
    private static final String SENDER_EMAIL = System.getenv("SENDER_EMAIL"); // 已在 SES 中验证的发件人邮箱
    private static final String RECIPIENT_EMAIL = System.getenv("RECIPIENT_EMAIL"); //

    // AWS 区域
    private static final String AWS_REGION = "us-east-1"; // 替换为您的 SES 所在区域

    // 主题
    private static final String EMAIL_SUBJECT = "AWS Lambda Logs";

    private final AmazonSimpleEmailService sesClient;
    private final ObjectMapper objectMapper;

    public LogToEmailHandler() {
        this.sesClient = AmazonSimpleEmailServiceClientBuilder.standard()
                .withRegion(AWS_REGION)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String handleRequest(CloudWatchLogsEvent event, Context context) {
        try {
            // 解码 Base64 数据
            String data = event.getAwsLogs().getData();
            byte[] decodedBytes = Base64.getDecoder().decode(data);

            // 解压缩数据
            String jsonLogs = decompressGZIP(decodedBytes);

            // 解析日志内容
            JsonNode root = objectMapper.readTree(jsonLogs);
            JsonNode logEvents = root.path("logEvents");

            StringBuilder logContentBuilder = new StringBuilder();
            if (logEvents.isArray()) {
                for (JsonNode logEvent : logEvents) {
                    String message = logEvent.path("message").asText();
                    logContentBuilder.append(message).append("\n");
                }
            }

            String logContent = logContentBuilder.toString();

            // 发送邮件
            sendEmail(logContent, context);

            return "Logs successfully sent via email.";
        } catch (Exception e) {
            context.getLogger().log("Error processing logs: " + e.getMessage());
            return "Error processing logs: " + e.getMessage();
        }
    }

    /**
     * 解压缩 GZIP 数据
     *
     * @param compressedData 压缩后的字节数组
     * @return 解压缩后的字符串
     * @throws IOException
     */
    private String decompressGZIP(byte[] compressedData) throws IOException {
        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(compressedData);
             GZIPInputStream gzipIn = new GZIPInputStream(byteIn);
             InputStreamReader reader = new InputStreamReader(gzipIn, StandardCharsets.UTF_8);
             BufferedReader in = new BufferedReader(reader)) {

            StringBuilder outStr = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                outStr.append(line);
            }
            return outStr.toString();
        }
    }

    /**
     * 发送邮件的方法
     *
     * @param logContent 日志内容
     * @param context    Lambda 上下文
     */
    private void sendEmail(String logContent, Context context) {
        try {
            Destination destination = new Destination().withToAddresses(RECIPIENT_EMAIL);

            Content subject = new Content().withData(EMAIL_SUBJECT);
            Content textBody = new Content().withData(logContent);
            Body body = new Body().withText(textBody);

            Message message = new Message().withSubject(subject).withBody(body);

            SendEmailRequest request = new SendEmailRequest()
                    .withSource(SENDER_EMAIL)
                    .withDestination(destination)
                    .withMessage(message);

            sesClient.sendEmail(request);
            context.getLogger().log("Logs have been sent via email.");
            context.getLogger().log("CICD测试222");
            context.getLogger().log("CICD测试222");
            context.getLogger().log("CICD测试222");
            context.getLogger().log("CICD测试222");
            context.getLogger().log("CICD测试222");
            context.getLogger().log("CICD测试222");
            context.getLogger().log("CICD测试222");

        } catch (Exception e) {
            context.getLogger().log("Failed to send email: " + e.getMessage());
        }
    }
}
