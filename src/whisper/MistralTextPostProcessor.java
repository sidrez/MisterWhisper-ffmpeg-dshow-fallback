package whisper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSON;
import org.json.JSONObject;

public class MistralTextPostProcessor {
    private static final String API_KEY_ENV = "MISTRAL_API_KEY";
    private static final String BASE_URL = "https://api.mistral.ai/v1/";
    private static final String CHAT_COMPLETIONS_PATH = "chat/completions";
    private static final String MODEL = "mistral-small-2603";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final String PROMPT_RESOURCE = "/whisper/mistral-post-processing-prompt.txt";
    private static final String PROMPT_FILE = "src/whisper/mistral-post-processing-prompt.txt";
    private static final String FINAL_TEXT_OPEN_TAG = "<final-text>";
    private static final String FINAL_TEXT_CLOSE_TAG = "</final-text>";

    private final String prompt;
    private String promptSource = "unknown";
    private boolean missingApiKeyWarned;

    public MistralTextPostProcessor() {
        this.prompt = loadPrompt();
    }

    public String postProcess(String text, boolean debug) {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }

        String apiKey = System.getenv(API_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            if (!this.missingApiKeyWarned) {
                Logger.getGlobal().warning(API_KEY_ENV + " is not set; skipping Post-processing");
                this.missingApiKeyWarned = true;
            }
            return text;
        }

        try {
            if (debug) {
                System.out.println("Mistral Post-processing prompt source: " + this.promptSource);
            }
            long startedAt = System.currentTimeMillis();
            String processed;
            try {
                processed = request(text, apiKey);
            } catch (SocketTimeoutException ex) {
                if (debug) {
                    System.out.println("Mistral Post-processing timed out; retrying once");
                }
                processed = request(text, apiKey);
            }
            long elapsed = System.currentTimeMillis() - startedAt;
            if (processed == null || processed.trim().isEmpty()) {
                if (debug) {
                    System.out.println("Mistral Post-processing returned empty text; using original text");
                }
                return text;
            }
            if (debug) {
                System.out.println("Mistral Post-processing completed in " + elapsed + " ms; changed=" + !text.equals(processed.trim()));
                System.out.println("Mistral Post-processing result: " + processed.trim());
            }
            return processed.trim();
        } catch (Exception ex) {
            if (debug) {
                System.out.println("Mistral Post-processing failed; using original text: " + ex.getMessage());
            }
            return text;
        }
    }

    private String request(String text, String apiKey) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(BASE_URL + CHAT_COMPLETIONS_PATH);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");

            byte[] payload = createPayload(text).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int responseCode = connection.getResponseCode();
            InputStream responseStream = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = readResponse(responseStream);
            if (responseCode >= 400) {
                throw new IOException("Mistral API returned HTTP " + responseCode + ": " + response);
            }
            return extractFinalText(response);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String createPayload(String text) {
        JSONObject request = new JSONObject();
        request.put("model", MODEL);
        request.put("temperature", 0);

        JSONArray messages = new JSONArray();

        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", this.prompt);
        messages.add(systemMessage);

        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", text);
        messages.add(userMessage);

        request.put("messages", messages);
        return request.toString();
    }

    private String extractFinalText(String response) {
        JSONObject obj = (JSONObject) JSON.parse(response);
        JSONArray choices = obj.optJSONArray("choices");
        if (choices == null || choices.size() == 0) {
            throw new IllegalStateException("Mistral response has no choices");
        }

        JSONObject firstChoice = choices.optJSONObject(0);
        if (firstChoice == null) {
            throw new IllegalStateException("Mistral response choice is not an object");
        }

        JSONObject message = firstChoice.optJSONObject("message");
        if (message == null) {
            throw new IllegalStateException("Mistral response choice has no message");
        }

        String content = message.optString("content", "").trim();
        if (content.isEmpty()) {
            return content;
        }

        int start = content.indexOf(FINAL_TEXT_OPEN_TAG);
        int end = content.lastIndexOf(FINAL_TEXT_CLOSE_TAG);
        if (start >= 0 && end > start) {
            start += FINAL_TEXT_OPEN_TAG.length();
            return content.substring(start, end).trim();
        }

        return content;
    }

    private String readResponse(InputStream responseStream) throws IOException {
        if (responseStream == null) {
            return "";
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    private String loadPrompt() {
        File promptFile = new File(PROMPT_FILE);
        if (promptFile.isFile()) {
            try (InputStream in = new FileInputStream(promptFile)) {
                this.promptSource = promptFile.getAbsolutePath();
                return readResponse(in).trim();
            } catch (IOException e) {
                Logger.getGlobal().warning("Cannot read Mistral prompt file: " + e.getMessage());
            }
        }

        try (InputStream in = getClass().getResourceAsStream(PROMPT_RESOURCE)) {
            if (in != null) {
                this.promptSource = PROMPT_RESOURCE;
                return readResponse(in).trim();
            }
        } catch (IOException e) {
            Logger.getGlobal().warning("Cannot read Mistral prompt resource: " + e.getMessage());
        }

        Logger.getGlobal().warning("Mistral prompt file not found; using built-in fallback prompt");
        this.promptSource = "built-in fallback";
        return "Correct only spelling, punctuation, grammar and obvious typos. Do not change language, meaning, names or terms. Return only the corrected text in <final-text>...</final-text>.";
    }
}
