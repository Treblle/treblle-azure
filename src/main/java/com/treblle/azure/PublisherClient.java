package com.treblle.azure;

import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.treblle.azure.dto.RuntimeError;
import com.treblle.azure.dto.TrebllePayload;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import com.microsoft.azure.functions.*;


/**
 * PublisherClient is responsible for sending events.
 */
public class PublisherClient {

    // Logger for logging messages
    private static final Logger log = LoggerFactory.getLogger(PublisherClient.class);

    // Array of base URLs for the Treblle service
    private static final String[] BASE_URLS = {
            "https://rocknrolla.treblle.com",
            "https://punisher.treblle.com",
            "https://sicario.treblle.com"
    };

    // Array of keywords to be masked in the payload
    private static final String[] MASK_KEYWORDS = {
            "password", "pwd", "secret", "password_confirmation", "cc", "card_number", "ccv", "ssn", "credit_score"};
    List<String> maskKeywordsList = new ArrayList<>(Arrays.asList(MASK_KEYWORDS));

    // API key for authentication
    private String apiKey;

    // Project ID for the Treblle project
    private String projectId;


    /**
     * Constructor to initialize the PublisherClient with API key and project ID.
     *
     * @param apiKey    the API key for authentication
     * @param projectId the project ID for the Treblle project
     */
    public PublisherClient(String apiKey, String projectId, ExecutionContext context) {
        context.getLogger().info("Initializing PublisherClient...");
        context.getLogger().info("API Key provided: " + (apiKey != null ? "YES (length: " + apiKey.length() + ")" : "NO"));
        context.getLogger().info("Project ID provided: " + (projectId != null ? "YES (length: " + projectId.length() + ")" : "NO"));
        
        this.apiKey = apiKey;
        this.projectId = projectId;

        // Retrieve additional mask keywords from environment variable
        context.getLogger().info("Retrieving additional mask keywords from environment...");
        String maskKeywordsEnv = System.getenv("ADDITIONAL_MASK_KEYWORDS");
        context.getLogger().info("Additional mask keywords env var: " + (maskKeywordsEnv != null ? maskKeywordsEnv : "NOT SET"));
        
        if (maskKeywordsEnv != null) {
            String[] maskKeywordsEnvArray = maskKeywordsEnv.split(",");
            maskKeywordsList.addAll(Arrays.asList(maskKeywordsEnvArray));
        }

        context.getLogger().info("Total masking keywords: " + maskKeywordsList.size());
        context.getLogger().info("PublisherClient initialized successfully");
    }


    /**
     * Attempts to retry publishing the payload with exponential backoff.
     * This method decrements the retry attempt counter and waits 10 seconds
     * before attempting to publish again. If all retry attempts are exhausted,
     * the event is dropped and an error is logged.
     *
     * @param payload the TrebllePayload object to be republished
     */
    private void doRetry(TrebllePayload payload, ExecutionContext context) {

        Integer currentAttempt = PublisherClientContextHolder.PUBLISH_ATTEMPTS.get();
        context.getLogger().info("CURRENT ATTEMPT: " + currentAttempt);
        if (currentAttempt > 0) {
            currentAttempt -= 1;
            PublisherClientContextHolder.PUBLISH_ATTEMPTS.set(currentAttempt);
            try {
                Thread.sleep(10000);
                publish(payload, context);
            } catch (InterruptedException e) {
                  log.error("Failing retry attempt at Publisher client", e);
            }
        } else if (currentAttempt == 0) {
              log.error("Failed all retrying attempts. Event will be dropped for project id: " + projectId);
        }
    }

    /**
     * Publishes a TrebllePayload to the Treblle service.
     * This method sets the API key and project ID on the payload,
     * selects a random base URL from the available endpoints,
     * and attempts to send the payload. Based on the HTTP response
     * status code, it either logs success or initiates retry logic.
     *
     * @param payload the TrebllePayload object containing the event data to be published
     */
    public void publish(TrebllePayload payload, ExecutionContext context) {
          context.getLogger().info("=== Starting publish method ===");
          context.getLogger().info("Payload received: " + (payload != null ? "YES" : "NO"));
               ObjectMapper objectMapper = new ObjectMapper();
        if (payload == null) {
              log.error("Payload is null, cannot publish");
            return;
        }

          context.getLogger().info("Payload internal ID: " + payload.getInternalId());
          context.getLogger().info("Setting API Key and Project ID on payload...");
        
        // Setting API Key and Project ID
        payload.setApiKey(apiKey);
        payload.setProjectId(projectId);
        context.getLogger().info("apiKey: " + apiKey);
        context.getLogger().info("projectId: " + projectId);
        
          context.getLogger().info("API Key and Project ID set successfully");

          context.getLogger().info("Getting random base URL...");
        String randomBaseUrl = getRandomBaseUrl();
          context.getLogger().info("Selected base URL: " + randomBaseUrl);
        
          context.getLogger().info("Calling maskAndSendPayload...");
        try {
            context.getLogger().info("Payload: " + objectMapper.writeValueAsString(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            context.getLogger().severe("Failed to serialize payload: " + e.getMessage());
        }
        CloseableHttpResponse response = maskAndSendPayload(payload, randomBaseUrl, context);

        int statusCode = 0;
        context.getLogger().info("RESPONSE IS HERE: " + response);  
        if (response != null) {
            statusCode = response.getStatusLine().getStatusCode();
              context.getLogger().info("HTTP response received with status code: " + statusCode);
        } else {
              log.warn("HTTP response is null");
        }

        if (statusCode == 200 || statusCode == 201 || statusCode == 202 || statusCode == 204) {
              context.getLogger().info("Event successfully published with status code: " + statusCode);
        } else if (statusCode >= 400 && statusCode < 500) {
              log.error("Event publishing failed for project id: " + projectId + " with status code: " + statusCode
                    + " and reason: "
                    + response.getStatusLine().getReasonPhrase());
        } else {
              log.error("Event publishing failed for project id: " + projectId + ". Retrying...");
            doRetry(payload, context);
        }
          context.getLogger().info("=== Ending publish method ===");
    }

    /**
     * Selects a random base URL from the predefined list of Treblle service endpoints.
     * This provides load balancing across multiple Treblle service instances.
     *
     * @return a randomly selected base URL string from the BASE_URLS array
     */
    private static String getRandomBaseUrl() {
        Random random = new Random();
        int index = random.nextInt(BASE_URLS.length);
        return BASE_URLS[index];
    }

    /**
     * Masks sensitive data in the payload and sends it to the Treblle service.
     * This method creates an HTTP client, builds the request with proper headers,
     * applies keyword masking to sensitive fields, and executes the HTTP POST request.
     *
     * @param payload the TrebllePayload object containing the data to be sent
     * @param baseUrl the base URL of the Treblle service endpoint to send the request to
     * @return the CloseableHttpResponse from the Treblle service, or null if an error occurs
     */
    private CloseableHttpResponse maskAndSendPayload(TrebllePayload payload, String baseUrl, ExecutionContext context) {
        context.getLogger().info("=== Starting maskAndSendPayload ===");
        context.getLogger().info("Payload: " + (payload != null ? "NOT NULL" : "NULL"));
        context.getLogger().info("Base URL: " + baseUrl);

        final List<RuntimeError> errors = new ArrayList<>(2);
        if (!errors.isEmpty()) {
            payload.getData().setErrors(errors);
        }

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            context.getLogger().info("HTTP client created successfully");
            context.getLogger().info("Creating HTTP POST request...");
            
            HttpPost httpPost = new HttpPost(baseUrl);
            context.getLogger().info("Setting headers...");
            httpPost.setHeader("x-api-key", payload.getApiKey());
            context.getLogger().info("API Key header set: " + payload.getApiKey());
             httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
            context.getLogger().info("Headers set successfully");
            
            context.getLogger().info("Building request body...");
            org.json.JSONObject requestBody = buildRequestBodyForTrebllePayload(payload);
            context.getLogger().info("Request body built successfully");
            log.debug("Treblle Payload - " + requestBody);
            
            context.getLogger().info("Creating string entity...");
            StringEntity params = new StringEntity(requestBody.toString(), "UTF-8");
            httpPost.setEntity(params);
            context.getLogger().info("String entity set successfully");
            
            context.getLogger().info("Executing HTTP request...");

            CloseableHttpResponse response = httpClient.execute(httpPost);

            context.getLogger().info("HTTP request executed successfully");
            context.getLogger().info("Response: " + response);
            return response;
        } catch (IOException e) {
            log.error("IOException in maskAndSendPayload: " + e.getMessage(), e);
            context.getLogger().info("IOException: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected exception in maskAndSendPayload: " + e.getMessage(), e);
            log.error("Exception class: " + e.getClass().getName());
            context.getLogger().info("Exception found hererere: " + e.getMessage() + " - " + e.getClass().getName());
        }

        log.warn("Returning null response from maskAndSendPayload");
        return null;
    }

    /**
     * Converts a TrebllePayload object into a JSON request body format expected by the Treblle API.
     * This method extracts all relevant data from the payload including request, response,
     * server information, and errors, then applies keyword masking to sensitive fields.
     *
     * @param trebllePayload the TrebllePayload object containing all event data
     * @return a JSONObject representing the complete request body for the Treblle API
     * @throws IllegalArgumentException if trebllePayload is null
     * @throws IllegalStateException if required data fields (request, response) are null
     */
    private org.json.JSONObject buildRequestBodyForTrebllePayload(TrebllePayload trebllePayload) {
        log.info("=== Starting buildRequestBodyForTrebllePayload ===");
        log.info("TrebllePayload: " + (trebllePayload != null ? "NOT NULL" : "NULL"));
        
        if (trebllePayload == null) {
            log.error("TrebllePayload is null in buildRequestBodyForTrebllePayload");
            throw new IllegalArgumentException("TrebllePayload cannot be null");
        }

        try {
            log.info("Creating main request body JSON object...");
            org.json.JSONObject requestBody = new org.json.JSONObject();
            requestBody.put("api_key", trebllePayload.getApiKey());
            requestBody.put("project_id", trebllePayload.getProjectId());
            requestBody.put("internal_id", trebllePayload.getInternalId());
            requestBody.put("sdk", trebllePayload.getSdk());
            requestBody.put("version", trebllePayload.getVersion());
            log.info("Main request body created successfully");

            log.info("Getting data from payload...");
            if (trebllePayload.getData() == null) {
                log.error("TrebllePayload.getData() is null");
                throw new IllegalStateException("TrebllePayload data cannot be null");
            }

            log.info("Creating data JSON object...");
            org.json.JSONObject data = new org.json.JSONObject();
            

            log.info("Creating request JSON object...");
            if (trebllePayload.getData().getRequest() == null) {
                log.error("Request data is null");
                throw new IllegalStateException("Request data cannot be null");
            }
            
            org.json.JSONObject request = new org.json.JSONObject();
            request.put("timestamp", trebllePayload.getData().getRequest().getTimestamp());
            request.put("ip", trebllePayload.getData().getRequest().getIp());
            request.put("user_agent", trebllePayload.getData().getRequest().getUserAgent());
            request.put("method", trebllePayload.getData().getRequest().getMethod());
            request.put("url", trebllePayload.getData().getRequest().getUrl());
            
            log.info("Adding request headers...");
            if (trebllePayload.getData().getRequest().getHeaders() != null) {
                request.put("headers", new org.json.JSONObject(trebllePayload.getData().getRequest().getHeaders()));
            } else {
                log.warn("Request headers are null, using empty object");
                request.put("headers", new org.json.JSONObject());
            }

            log.info("Adding request body...");
            JsonNode reqBody = trebllePayload.getData().getRequest().getBody();
            if (reqBody != null) {
                if (reqBody.isObject()) {
                    request.put("body", new org.json.JSONObject(reqBody.toString()));
                } else if (reqBody.isArray()) {
                    request.put("body", new org.json.JSONArray(reqBody.toString()));
                } else {
                    // For primitive values (string, number, boolean, null)
                    request.put("body", reqBody.asText());
                }
            } else {
                request.put("body", new org.json.JSONObject());
            }

            data.put("request", request);
            log.info("Request JSON object created successfully");

            log.info("Creating response JSON object...");
            if (trebllePayload.getData().getResponse() == null) {
                log.error("Response data is null");
                throw new IllegalStateException("Response data cannot be null");
            }
            
            org.json.JSONObject response = new org.json.JSONObject();
            response.put("code", trebllePayload.getData().getResponse().getCode());
            response.put("size", trebllePayload.getData().getResponse().getSize());
            
            log.info("Adding response headers...");
            if (trebllePayload.getData().getResponse().getHeaders() != null) {
                response.put("headers", new org.json.JSONObject(trebllePayload.getData().getResponse().getHeaders()));
            } else {
                log.warn("Response headers are null, using empty object");
                response.put("headers", new org.json.JSONObject());
            }
            
            response.put("load_time", trebllePayload.getData().getResponse().getLoadTime());

            log.info("Adding response body...");
            JsonNode responseBody = trebllePayload.getData().getResponse().getBody();
            if (responseBody != null) {
                if (responseBody.isObject()) {
                    response.put("body", new org.json.JSONObject(responseBody.toString()));
                } else if (responseBody.isArray()) {
                    response.put("body", new org.json.JSONArray(responseBody.toString()));
                } else {
                    // For primitive values (string, number, boolean, null)
                    response.put("body", responseBody.asText());
                }
            } else {
                response.put("body", new org.json.JSONObject());
            }

            data.put("response", response);
            log.info("Response JSON object created successfully");
            
            log.info("Adding server data...");
            if (trebllePayload.getData().getServer() != null) {
                data.put("server", new org.json.JSONObject(trebllePayload.getData().getServer()));
            } else {
                log.warn("Server data is null, using empty object");
                data.put("server", new org.json.JSONObject());
            }
            
            log.info("Adding errors data...");
            if (trebllePayload.getData().getErrors() != null) {
                data.put("errors", new org.json.JSONArray(trebllePayload.getData().getErrors()));
            } else {
                log.warn("Errors data is null, using empty array");
                data.put("errors", new org.json.JSONArray());
            }

            log.info("Starting keyword masking...");
            for (String keyword : maskKeywordsList) {
                maskKeywordInJson(data, keyword);
            }
            log.info("Keyword masking completed");
            
            requestBody.put("data", data);
            log.info("Request body built successfully");
            
            return requestBody;
            
        } catch (Exception e) {
            log.error("Exception in buildRequestBodyForTrebllePayload: " + e.getMessage(), e);
            log.error("Exception class: " + e.getClass().getName());
            throw e;
        }
    }

    /**
     * Recursively masks sensitive keywords in a JSON object by replacing their values with "****".
     * This method performs case-insensitive matching of keys against the provided keyword
     * and recursively processes nested JSON objects to ensure complete masking of sensitive data.
     *
     * @param jsonObject the JSON object to scan and mask sensitive fields in
     * @param keyword the sensitive keyword to search for and mask (case-insensitive)
     */
    private void maskKeywordInJson(org.json.JSONObject jsonObject, String keyword) {
        String lowerCaseKeyword = keyword.toLowerCase();
        for (Object key : jsonObject.keySet()) {
            String lowerCaseKey = key.toString().toLowerCase();
            if (lowerCaseKey.equals(lowerCaseKeyword)) {
                jsonObject.put(key.toString(), "****");
            } else {
                Object value = jsonObject.get(key.toString());
                if (value instanceof org.json.JSONObject) {
                    maskKeywordInJson((org.json.JSONObject) value, keyword);
                }
            }
        }
    }

}