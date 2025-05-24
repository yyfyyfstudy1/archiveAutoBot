package com.ruoyi;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.ruoyi.config.ConfigLoader;
import com.ruoyi.config.DocumentConfig;
import com.ruoyi.config.DocumentType;
import com.ruoyi.util.DocumentTypeDetector;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.poi.xwpf.usermodel.*;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.mail.*;
import javax.mail.internet.MimeMessage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.api.services.drive.model.File;
import software.amazon.awssdk.services.ssm.SsmClient;

public class WordFillHandler implements RequestHandler<S3Event, String> {


    private static final String MODEL = System.getenv("GPT_MODEL"); // 使用的模型
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    // 从环境变量中读取Google服务账户凭证
    private static final String credentialsJson = System.getenv("GOOGLE_CREDENTIALS");
    // 从环境变量获取API Key
    private static final String folderId = System.getenv("GOOGLE_DRIVE_FOLDER_ID");



    private static final String OUTPUT_BUCKET_NAME = "word-output-bucket-yyf"; // 替换为存储填充后文档的桶名
    private static final String OUTPUT_KEY_PREFIX = "filled_documents/"; // 输出文件的前缀，例如根据邮件文件名生成

    private static final String OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"; // 使用chat/completions端点

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    private final S3Client s3Client;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ConfigLoader configLoader;

    public WordFillHandler() throws IOException {
        this.s3Client = S3Client.builder()
                .region(Region.US_EAST_1) // 替换为区域
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .build();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS) // 连接超时
                .writeTimeout(60, TimeUnit.SECONDS)   // 写入超时
                .readTimeout(60, TimeUnit.SECONDS)    // 读取超时
                .build();
        this.objectMapper = new ObjectMapper();
        // 初始化 SsmClient
        SsmClient ssmClient = SsmClient.builder()
                .region(Region.US_EAST_1) // 区域
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .build();
        // 初始化 ConfigLoader
        this.configLoader = new ConfigLoader(ssmClient, "/myapp/document_config"); // parameter store名称
    }

    @Override
    public String handleRequest(S3Event s3event, Context context) {
        context.getLogger().log("Lambda函数开始执行九月6号更新。");

        // 1. 获取 S3 事件信息
        String sourceBucket = "";
        String sourceKey = "";
        try {
            List<S3EventNotification.S3EventNotificationRecord> records = s3event.getRecords();
            if (records != null && !records.isEmpty()) {
                S3EventNotification.S3EventNotificationRecord record = records.get(0);
                S3EventNotification.S3Entity s3Entity = record.getS3();
                S3EventNotification.S3ObjectEntity s3Object = s3Entity.getObject();
                sourceBucket = s3Entity.getBucket().getName();
                sourceKey = s3Object.getKey();
            } else {
                context.getLogger().log("未找到S3事件记录。");
                return "未找到S3事件记录。";
            }
        } catch (Exception e) {
            context.getLogger().log("解析S3事件失败: " + e.getMessage());
            return "解析S3事件失败: " + e.getMessage();
        }

        context.getLogger().log("处理的S3存储桶: " + sourceBucket + ", 文件键: " + sourceKey);

        try {
            // 2. 从 S3 读取 MIME 文件
            context.getLogger().log("从S3读取MIME文件。桶名: " + sourceBucket + ", 文件键: " + sourceKey);
            ResponseInputStream<software.amazon.awssdk.services.s3.model.GetObjectResponse> s3Object = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(sourceBucket)
                    .key(sourceKey)
                    .build());

            // 使用 JavaMail 解析 MIME 内容
            Session session = Session.getDefaultInstance(new Properties());
            MimeMessage mimeMessage = new MimeMessage(session, s3Object);
            String emailBody = extractEmailBody(mimeMessage);
            context.getLogger().log("提取的邮件正文内容: " + emailBody);

            if (emailBody == null || emailBody.isEmpty()) {
                context.getLogger().log("未提取到邮件正文内容。");
                return "未提取到邮件正文内容。";
            }

            // 3. 确定文档类型
            DocumentType documentType = DocumentTypeDetector.detect(emailBody, configLoader.getDocumentTypes());
            if (documentType == null) {
                context.getLogger().log("无法确定文档类型。");
                return "无法确定文档类型。";
            }

            context.getLogger().log("检测到的文档类型: " + documentType.getType());

            // 4. 加载文档类型配置
            DocumentConfig config = configLoader.getConfig(documentType);
            if (config == null) {
                context.getLogger().log("未找到文档类型的配置。类型: " + documentType);
                return "未找到文档类型的配置。";
            }

            // 5. 从模板桶读取 Word 模板
            context.getLogger().log("从模板桶读取Word模板。桶名: " + config.getTemplateBucketName() + ", 文件键: " + config.getTemplateKey());
            ResponseInputStream<software.amazon.awssdk.services.s3.model.GetObjectResponse> templateObject = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(config.getTemplateBucketName())
                    .key(config.getTemplateKey())
                    .build());

            XWPFDocument document = new XWPFDocument(templateObject);
            context.getLogger().log("成功读取Word模板。");

            // 6. 提取模板中的占位符
            context.getLogger().log("提取模板中的占位符。");
            List<String> placeholdersList = extractPlaceholders(document, context);

            if (placeholdersList.isEmpty()) {
                context.getLogger().log("未找到任何占位符。");
                return "没有需要填充的占位符。";
            }

            // 7. 调用 OpenAI API 生成内容
            Map<String, String> generatedContents = getGeneratedContents(context, config.getPrompt(), placeholdersList, emailBody, documentType);

            context.getLogger().log("生成的内容: " + generatedContents.toString());

            // 8. 填充生成的内容到 Word 文档
            context.getLogger().log("填充生成的内容到Word文档。");
            fillDocument(document, generatedContents, context);

            // 9. 将填充后的文档保存到临时文件
            context.getLogger().log("将填充后的文档保存到临时文件。");
            java.io.File tempFile = java.io.File.createTempFile("filled_document_", ".docx"); // 修改为固定前缀，避免使用config.getFileName()导致的UUID问题
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                document.write(out);
            }
            context.getLogger().log("临时文件已创建: " + tempFile.getAbsolutePath());

            // 10. 定义期望的上传文件名（不包含UUID）
            String desiredFileName = config.getFileName() + ".docx"; // 使用配置中的文件名

            // 12. 上传文件到Google Drive
            context.getLogger().log(uploadGoogleDrive(tempFile, config.getGoogleFolderId(), desiredFileName)); // 传递期望的文件名

            // 11. 生成输出文件键（可根据需要调整，例如使用邮件文件名）
            String outputKey = OUTPUT_KEY_PREFIX + config.getFileName() + ".docx"; // 修改为使用期望的文件名

            // 13. 上传填充后的文档回输出桶
            context.getLogger().log("上传填充后的文档回S3。桶名: " + OUTPUT_BUCKET_NAME + ", 文件键: " + outputKey);
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(OUTPUT_BUCKET_NAME)
                            .key(outputKey)
                            .build(),
                    RequestBody.fromFile(tempFile));
            context.getLogger().log("成功上传填充后的文档到S3。");


            // 删除临时文件
            if (tempFile.delete()) {
                context.getLogger().log("临时文件已删除。");
            } else {
                context.getLogger().log("无法删除临时文件: " + tempFile.getAbsolutePath());
            }

            context.getLogger().log("Lambda函数执行完成。");
            return "Word文档已成功填充并上传到S3。";

        } catch (Exception e) {
            context.getLogger().log("发生错误: " + e.getMessage());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            context.getLogger().log(sw.toString());
            return "发生错误: " + e.getMessage();
        }
    }

    // 提取模板中的占位符
    private List<String> extractPlaceholders(XWPFDocument document, Context context) {
        List<String> placeholdersList = new ArrayList<>();

        // 提取段落中的占位符
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            String text = paragraph.getText();
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
            while (matcher.find()) {
                String placeholder = matcher.group(1).trim();
                if (!placeholdersList.contains(placeholder)) { // 避免重复
                    placeholdersList.add(placeholder);
                    context.getLogger().log("找到占位符: " + placeholder);
                }
            }
        }

        // 提取表格中的占位符
        for (XWPFTable table : document.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph para : cell.getParagraphs()) {
                        String cellText = para.getText();
                        Matcher matcher = PLACEHOLDER_PATTERN.matcher(cellText);
                        while (matcher.find()) {
                            String placeholder = matcher.group(1).trim();
                            if (!placeholdersList.contains(placeholder)) {
                                placeholdersList.add(placeholder);
                                context.getLogger().log("在表格中找到占位符: " + placeholder);
                            }
                        }
                    }
                }
            }
        }

        return placeholdersList;
    }

    // 填充文档中的占位符
    private void fillDocument(XWPFDocument document, Map<String, String> generatedContents, Context context) {
        // 填充段落中的占位符
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            replacePlaceholdersInParagraph(paragraph, generatedContents);
        }

        // 填充表格中的占位符
        for (XWPFTable table : document.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph paragraph : cell.getParagraphs()) {
                        replacePlaceholdersInParagraph(paragraph, generatedContents);
                    }
                }
            }
        }
    }

    // 填充文档函数
    private void replacePlaceholdersInParagraph(XWPFParagraph paragraph, Map<String, String> generatedContents) {
        String text = paragraph.getText();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuffer replacedParagraph = new StringBuffer();
        boolean found = false;

        while (matcher.find()) {
            found = true;
            String placeholder = matcher.group(1).trim();
            String replacement = generatedContents.getOrDefault(placeholder, "");
            replacement = Matcher.quoteReplacement(replacement);
            matcher.appendReplacement(replacedParagraph, replacement);
        }

        if (found) {
            matcher.appendTail(replacedParagraph);
            List<XWPFRun> runs = paragraph.getRuns();
            if (!runs.isEmpty()) {
                XWPFRun run = runs.get(0);
                run.setText(replacedParagraph.toString(), 0); // 替换第一个run的文本，尽量保持格式
                for (int i = runs.size() - 1; i > 0; i--) {
                    paragraph.removeRun(i); // 移除其他的runs
                }
            } else {
                paragraph.createRun().setText(replacedParagraph.toString());
            }
        }
    }

    // 获取生成的内容
    private Map<String, String> getGeneratedContents(Context context,
                                                     String promptTemplate,
                                                     List<String> placeholdersList,
                                                     String emailBody,
                                                     DocumentType documentType) throws IOException {

        // 构建提示词
        String prompt = String.format(promptTemplate, emailBody);

        context.getLogger().log("第一次调用OpenAI API的提示内容: " + prompt);
        String generatedContent = callOpenAI(prompt, context, null);
        context.getLogger().log("第一次从OpenAI获取的生成内容: " + generatedContent);
        

        String[] paragraphs = generatedContent.split("\n");  // 每个部分由换行符分隔


        // 分割生成内容为列表
        List<String> parts = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String cleanedParagraph = paragraph.trim();
            if (!cleanedParagraph.isEmpty()) {
                parts.add(cleanedParagraph);
            }
        }

        context.getLogger().log("分割内容后列表: " + parts);

        // 组装生成内容对应占位符
        Map<String, String> generatedContents = new HashMap<>();

        for (int i = 0; i < placeholdersList.size(); i++) {
            if (i < parts.size()) {
                generatedContents.put(placeholdersList.get(i), parts.get(i));
            } else {
                generatedContents.put(placeholdersList.get(i), ""); // 如果生成内容不足，填充空字符串
            }
        }
        return generatedContents;
    }

    /**
     * 从 MIME 消息中提取邮件正文内容（优先 HTML 部分，如果不存在则使用纯文本部分）
     *
     * @param mimeMessage MIME 消息对象
     * @return 邮件正文内容
     * @throws MessagingException
     * @throws IOException
     */
    private String extractEmailBody(MimeMessage mimeMessage) throws MessagingException, IOException {
        if (mimeMessage.isMimeType("text/plain")) {
            return mimeMessage.getContent().toString();
        } else if (mimeMessage.isMimeType("text/html")) {
            return mimeMessage.getContent().toString();
        } else if (mimeMessage.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) mimeMessage.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                if (bodyPart.isMimeType("text/plain")) {
                    return bodyPart.getContent().toString();
                } else if (bodyPart.isMimeType("text/html")) {
                    return bodyPart.getContent().toString();
                }
            }
        }
        return "";
    }

    // 调用 OpenAI API
    private String callOpenAI(String prompt, Context context, String previousResponse) throws IOException {
        MediaType mediaType = MediaType.parse("application/json");

        // 为避免 JSON 中的引号问题，正确地转义用户输入
        String escapedUserMessage = prompt.replace("\"", "\\\"");

        // 构建JSON请求体
        String json = buildJsonRequestBody(escapedUserMessage, previousResponse);

        context.getLogger().log("构建OpenAI API请求。");
        Request request = new Request.Builder()
                .url(OPENAI_ENDPOINT)
                .post(okhttp3.RequestBody.create(mediaType, json)) // 使用okhttp3.RequestBody
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + OPENAI_API_KEY)
                .build();
        context.getLogger().log("OpenAI API请求已构建。");

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                context.getLogger().log("OpenAI API请求失败，状态码: " + response.code());
                throw new IOException("Unexpected code " + response + ", Response: " + responseBody);
            }

            JsonNode jsonNode = objectMapper.readTree(responseBody);
            // 解析 chat/completions 的响应
            return jsonNode.get("choices").get(0).get("message").get("content").asText().trim();
        }
    }

    // 构建 OpenAI API 请求体
    private String buildJsonRequestBody(String userMessage, String previousResponse) {
        String systemInit = "\"role\": \"system\", \"content\": \"You are ChatGPT, a helpful assistant.\"";
        String userMessageJson = "\"role\": \"user\", \"content\": \"" + StringEscapeUtils.escapeJson(userMessage) + "\"";

        StringBuilder requestBody = new StringBuilder();
        requestBody.append("{")
                .append("\"model\": \"").append(MODEL).append("\",")
                .append("\"messages\": [")
                .append("{").append(systemInit).append("},");

        if (previousResponse != null && !previousResponse.isEmpty()) {
            String previousMessage = "\"role\": \"user\", \"content\": \"" + StringEscapeUtils.escapeJson(previousResponse) + "\"";
            requestBody.append("{").append(previousMessage).append("},");
        }

        requestBody.append("{").append(userMessageJson).append("}")
                .append("]")
                .append("}");
        return requestBody.toString();
    }

    /**
     * 上传文件到Google Drive的指定文件夹，按照当天日期创建子文件夹（如果不存在）。
     *
     * @param tempFile        要上传的文件
     * @param googleDriveFolderId Google Drive目标文件夹ID
     * @param desiredFileName 期望的文件名（不包含UUID）
     * @return 上传文件的ID或错误信息
     */
    public String uploadGoogleDrive(java.io.File tempFile, String googleDriveFolderId, String desiredFileName) { // 增加 desiredFileName 参数
        try {
            // 获取当前日期，格式为 "yyyy-MM-dd"
            String currentDate = new SimpleDateFormat("yyyy-MM-dd").format(new Date());

            // 初始化Google Drive服务
            InputStream credentialsStream = new ByteArrayInputStream(System.getenv("GOOGLE_CREDENTIALS").getBytes(StandardCharsets.UTF_8));

            GoogleCredential credential = GoogleCredential.fromStream(credentialsStream)
                    .createScoped(Collections.singletonList("https://www.googleapis.com/auth/drive.file"));

            Drive driveService = new Drive.Builder(new NetHttpTransport(), new JacksonFactory(), credential)
                    .setApplicationName("Google Drive API Java Lambda")
                    .build();

            // 获取或创建当天日期的文件夹
            String dateFolderId = getOrCreateDateFolder(driveService, currentDate, googleDriveFolderId);

            if (dateFolderId == null) {
                return "Error: Unable to create or retrieve the date folder.";
            }

            // 文件元数据
            File fileMetadata = new File();
            fileMetadata.setName(desiredFileName); // 使用期望的文件名
            // 设置Google Drive的目标文件夹ID
            fileMetadata.setParents(Collections.singletonList(dateFolderId));

            // 准备文件内容
            FileContent mediaContent = new FileContent("application/vnd.openxmlformats-officedocument.wordprocessingml.document", tempFile);

            // 执行文件上传
            File file = driveService.files().create(fileMetadata, mediaContent)
                    .setFields("id")
                    .execute();

            return "File uploaded to Google Drive with ID: " + file.getId();
        } catch (Exception e) {
            e.printStackTrace();
            return "Error during file upload: " + e.getMessage();
        }
    }

    /**
     * 获取指定名称的文件夹ID，如果不存在则创建一个新的文件夹。
     *
     * @param driveService  Google Drive服务实例
     * @param folderName    要获取或创建的文件夹名称
     * @param parentFolderId 父文件夹的ID
     * @return 文件夹的ID，如果创建或获取失败则返回null
     */
    private String getOrCreateDateFolder(Drive driveService, String folderName, String parentFolderId) {
        try {
            // 查询是否存在同名文件夹
            String query = "mimeType = 'application/vnd.google-apps.folder' and name = '" + folderName + "' and '" + parentFolderId + "' in parents and trashed = false";
            Drive.Files.List request = driveService.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("files(id, name)");

            List<File> folders = request.execute().getFiles();

            if (folders != null && !folders.isEmpty()) {
                // 文件夹已存在，返回其ID
                return folders.get(0).getId();
            } else {
                // 文件夹不存在，创建一个新的文件夹
                File fileMetadata = new File();
                fileMetadata.setName(folderName);
                fileMetadata.setMimeType("application/vnd.google-apps.folder");
                fileMetadata.setParents(Collections.singletonList(parentFolderId));

                File folder = driveService.files().create(fileMetadata)
                        .setFields("id")
                        .execute();

                return folder.getId();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

}
