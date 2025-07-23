package com.treblle.azure;

import java.util.Map;
import java.util.Base64;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treblle.azure.dto.*;

import com.microsoft.azure.functions.annotation.*;
import com.microsoft.azure.functions.*;

/**
 * Azure Functions with Event Hub trigger.
 * Processes incoming messages from Event Hub, validates them as Treblle logs,
 * and publishes them to the Treblle service with proper error handling and retry logic.
 */
public class EventHubReceiver {

    /**
     * Main Azure Function entry point triggered by Event Hub messages.
     * Processes batches of messages, validates configuration, and publishes valid Treblle logs.
     * 
     * @param messages Array of message strings received from Event Hub
     * @param propertiesArray Array of message properties from Event Hub
     * @param systemPropertiesArray Array of system properties from Event Hub
     * @param context Azure Functions execution context for logging and configuration
     */
    @FunctionName("EventHubReceiver")
    public void run(
            @EventHubTrigger(name = "message", eventHubName = "%eventhub%", consumerGroup = "%consumergroup%", connection = "eventhubconnection", cardinality = Cardinality.MANY) String[] messages,

            @BindingName("PropertiesArray") Map<String, Object>[] propertiesArray,
            @BindingName("SystemPropertiesArray") Map<String, Object>[] systemPropertiesArray,
            final ExecutionContext context) {

        if (messages == null || messages.length == 0) {
            context.getLogger().info("No messages received in batch");
            return;
        }

        context.getLogger().info("Event hub batch received with " + messages.length + " messages");
        
        // Log environment variable retrieval
        context.getLogger().info("Retrieving Treblle configuration from environment variables...");
        
        // Get Treblle configuration from environment variables
        String apiKey = System.getenv("TREBLLE_SDK_TOKEN");
        String projectId = System.getenv("TREBLLE_API_KEY");

        context.getLogger().info("TREBLLE_SDK_TOKEN present: " + (apiKey != null ? "YES" : "NO"));
        context.getLogger().info("TREBLLE_API_KEY present: " + (projectId != null ? "YES" : "NO"));

        if (apiKey == null || projectId == null || apiKey.trim().isEmpty() || projectId.trim().isEmpty()) {
            context.getLogger().severe("TREBLLE_API_KEY or TREBLLE_SDK_TOKEN not configured or empty. Cannot process messages.");
            return;
        }

        context.getLogger().info("Initializing PublisherClient...");
        PublisherClient publisherClient = null;
        try {
            publisherClient = new PublisherClient(apiKey, projectId, context);
            context.getLogger().info("PublisherClient initialized successfully");
        } catch (Exception e) {
            context.getLogger().severe("Failed to initialize PublisherClient: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        context.getLogger().info("Initializing ObjectMapper...");
        ObjectMapper objectMapper = new ObjectMapper();
        context.getLogger().info("ObjectMapper initialized successfully");

        int successCount = 0;
        int failureCount = 0;

        // Process each message in the batch
        for (int i = 0; i < messages.length; i++) {
            try {
                context.getLogger().info("=== Processing message " + (i + 1) + "/" + messages.length + " ===");
                String message = messages[i];
                context.getLogger().info("Message retrieved from array, length: " + (message != null ? message.length() : "NULL"));
                context.getLogger().info("Message - " + message);

                // Validate message is not null or empty
                if (message == null || message.trim().isEmpty()) {
                    context.getLogger().warning("Skipping null or empty message at index " + i);
                    failureCount++;
                    continue;
                }

                context.getLogger().info("Parsing JSON message...");
                // Parse the JSON message
                JsonNode messageNode = objectMapper.readTree(message);
                context.getLogger().info("JSON message parsed successfully");

                // Verify event type
                context.getLogger().info("Extracting event type...");
                String eventType = messageNode.path("event_type").asText();
                context.getLogger().info("Event type: " + eventType);
                
                if (!"treblle_log".equals(eventType)) {
                    context.getLogger().warning("Skipping message with event_type: " + eventType);
                    failureCount++;
                    continue;
                }

                context.getLogger().info("Building TrebllePayload...");
                // Extract and process the data
                TrebllePayload payload = buildTrebllePayload(messageNode, objectMapper, context);

                if (payload != null) {
                    context.getLogger().info("TrebllePayload built successfully, internal ID: " + payload.getInternalId());
                    
                    // Initialize retry context
                    context.getLogger().info("Initializing retry context...");
                    PublisherClientContextHolder.reset();

                    // Validate client before publishing
                    if (publisherClient == null) {
                        context.getLogger().severe("PublisherClient is null, cannot publish message " + (i + 1));
                        failureCount++;
                        continue;
                    }

                    // Publish to Treblle
                    context.getLogger().info("Publishing to Treblle...");
                 

                    try {
                        publisherClient.publish(payload, context);
                        context.getLogger().info("Successfully published message " + (i + 1));
                        successCount++;
                    } catch (Exception publishException) {
                        context.getLogger().severe("Failed to publish message " + (i + 1) + ": " + publishException.getMessage());
                        context.getLogger().severe("Exception class: " + publishException.getClass().getName());
                        publishException.printStackTrace();
                        failureCount++;
                    }
                } else {
                    context.getLogger().warning("TrebllePayload is null for message " + (i + 1));
                    failureCount++;
                }

            } catch (Exception e) {
                context.getLogger().severe("Error processing message " + (i + 1) + ": " + e.getMessage());
                context.getLogger().severe("Exception class: " + e.getClass().getName());
                context.getLogger().severe("Exception cause: " + (e.getCause() != null ? e.getCause().getMessage() : "None"));
                e.printStackTrace();
                failureCount++;
            } finally {
                context.getLogger().info("Cleaning up context for message " + (i + 1));
                // Clean up context
                PublisherClientContextHolder.clear();
            }
        }

        context.getLogger()
                .info("Batch processing completed - Success: " + successCount + ", Failures: " + failureCount);

    }

    /**
     * Builds a TrebllePayload object from an Event Hub message.
     * Extracts and transforms server, request, response data and handles error cases
     * based on HTTP status codes.
     * 
     * @param messageNode The parsed JSON message from Event Hub
     * @param objectMapper Jackson ObjectMapper for JSON processing
     * @param context Azure Functions execution context for logging
     * @return TrebllePayload object ready for publishing, or null if building fails
     */
    private TrebllePayload buildTrebllePayload(JsonNode messageNode, ObjectMapper objectMapper,
            ExecutionContext context) {
        try {
            TrebllePayload payload = new TrebllePayload();

            String internalId = messageNode.path("internal_id").asText();
            payload.setInternalId(internalId);
            Data data = new Data();

            // Extract server information
            JsonNode serverNode = messageNode.path("server");
            Server server = new Server();
            server.setTimezone(serverNode.path("timezone").asText("UTC"));
            server.setSignature(serverNode.path("signature").asText(""));
            server.setProtocol(serverNode.path("protocol").asText("https"));
            server.setEncoding(serverNode.path("encoding").asText("UTF-8"));

            // Extract OS information
            JsonNode osNode = serverNode.path("os");
            OperatingSystem os = new OperatingSystem();
            os.setName(osNode.path("name").asText("Unknown"));
            os.setRelease(osNode.path("release").asText("Unknown"));
            os.setArchitecture(osNode.path("architecture").asText("Unknown"));
            server.setOs(os);
            data.setServer(server);

            // Extract request information
            JsonNode requestNode = messageNode.path("request");
            Request request = new Request();

            String rawTimestamp = requestNode.path("timestamp").asText();
            String formattedTimestamp = formatTimestamp(rawTimestamp);
            request.setTimestamp(formattedTimestamp);

            request.setMethod(requestNode.path("method").asText());
            request.setIp(requestNode.path("ip_address").asText());
            request.setUrl(requestNode.path("original_url").asText());

            // Parse headers (they come as a delimited string)
            String headersString = requestNode.path("headers").asText("");
            Map<String, String> headers = parseHeaders(headersString);
            request.setHeaders(headers);

            request.setUserAgent(getUserAgentFromHeaders(headers));

            // Decode and set request body
            String requestBodyEncoded = requestNode.path("body").asText("");
            if (!requestBodyEncoded.isEmpty()) {
                try {
                    String decodedBody = new String(Base64.getDecoder().decode(requestBodyEncoded));
                    JsonNode bodyNode = objectMapper.readTree(decodedBody);
                    request.setBody(bodyNode);
                } catch (Exception e) {
                    context.getLogger().warning("Could not decode request body: " + e.getMessage());
                }
            }
            data.setRequest(request);

            // Extract response information
            JsonNode responseNode = messageNode.path("response");
            Response response = new Response();
            response.setCode(responseNode.path("status_code").asInt());
            response.setSize(responseNode.path("body_length").asLong());
            response.setLoadTime((double) calculateLoadTime(requestNode, responseNode));

            // Parse response headers
            String responseHeadersString = responseNode.path("headers").asText("");
            Map<String, String> responseHeaders = parseHeaders(responseHeadersString);
            response.setHeaders(responseHeaders);

            // Decode and set response body
            String responseBodyEncoded = responseNode.path("body").asText("");
            if (!responseBodyEncoded.isEmpty()) {
                try {
                    String decodedBody = new String(Base64.getDecoder().decode(responseBodyEncoded));
                    JsonNode bodyNode = objectMapper.readTree(decodedBody);
                    response.setBody(bodyNode);
                } catch (Exception e) {
                    context.getLogger().warning("Could not decode response body: " + e.getMessage());
                }
            }
            data.setResponse(response);

            // Handle errors based on status code
            List<RuntimeError> errors = new ArrayList<>();
            int statusCode = response.getCode();
            
            if (statusCode >= 400 && statusCode < 600) {
                RuntimeError error = new RuntimeError();
                error.setType("API Request failure");
                error.setSource("onError");
                error.setLine(0);
                
                // Extract error message from response body
                String errorMessage = extractErrorMessage(responseNode, objectMapper, context);
                error.setMessage(errorMessage);
                
                errors.add(error);
                context.getLogger().info("Added error for status code: " + statusCode + " with message: " + errorMessage);
            }
            
            data.setErrors(errors);

            payload.setData(data);
            return payload;

        } catch (Exception e) {
            context.getLogger().severe("Error building TrebllePayload: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the User-Agent header value from HTTP headers using case-insensitive lookup.
     * Searches through all header entries to find the User-Agent header regardless of case.
     * 
     * @param headers Map of HTTP headers where keys may have varying cases
     * @return User-Agent header value if found, empty string otherwise
     */
    private String getUserAgentFromHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }

        // Look for User-Agent header with case insensitive comparison
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("user-agent".equalsIgnoreCase(entry.getKey())) {
                return entry.getValue() != null ? entry.getValue() : "";
            }
        }

        return "";
    }

    /**
     * Converts an ISO 8601 timestamp to "yyyy-MM-dd HH:mm:ss" format.
     * Parses the input timestamp and formats it for Treblle API compatibility.
     * 
     * @param isoTimestamp ISO 8601 formatted timestamp string
     * @return Formatted timestamp string in "yyyy-MM-dd HH:mm:ss" format, or original string if parsing fails
     */
    private String formatTimestamp(String isoTimestamp) {
        try {
            if (isoTimestamp == null || isoTimestamp.trim().isEmpty()) {
                return "";
            }

            // Parse the ISO 8601 timestamp
            Instant instant = Instant.parse(isoTimestamp);

            // Convert to LocalDateTime in UTC
            LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

            // Format to desired pattern
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return dateTime.format(formatter);

        } catch (Exception e) {
            // Return original timestamp if parsing fails
            return isoTimestamp;
        }
    }

    /**
     * Parses a delimited header string into a Map of key-value pairs.
     * Headers are expected to be in format "key1:value1;;key2:value2;;..." 
     * 
     * @param headersString Delimited string containing header key-value pairs
     * @return Map containing parsed headers with trimmed keys and values
     */
    private Map<String, String> parseHeaders(String headersString) {
        Map<String, String> headers = new java.util.HashMap<>();
        if (headersString != null && !headersString.trim().isEmpty()) {
            String[] headerPairs = headersString.split(";;");
            for (String pair : headerPairs) {
                int colonIndex = pair.indexOf(':');
                if (colonIndex > 0 && colonIndex < pair.length() - 1) {
                    String key = pair.substring(0, colonIndex).trim();
                    String value = pair.substring(colonIndex + 1).trim();
                    headers.put(key, value);
                }
            }
        }
        return headers;
    }

    /**
     * Calculates the load time between request and response timestamps.
     * Parses ISO 8601 timestamps and returns the duration in microseconds.
     * 
     * @param requestNode JSON node containing request data with timestamp
     * @param responseNode JSON node containing response data with timestamp
     * @return Load time in microseconds, or 0 if calculation fails
     */
    private long calculateLoadTime(JsonNode requestNode, JsonNode responseNode) {
        try {
            String requestTime = requestNode.path("timestamp").asText();
            String responseTime = responseNode.path("timestamp").asText();

            // Parse timestamps and calculate difference in milliseconds
            java.time.Instant requestInstant = java.time.Instant.parse(requestTime);
            java.time.Instant responseInstant = java.time.Instant.parse(responseTime);

            return java.time.Duration.between(requestInstant, responseInstant).toMillis() * 1000;
        } catch (Exception e) {
            return 0; // Default to 0 if calculation fails
        }
    }

    /**
     * Extracts error message from response body for failed HTTP requests.
     * Attempts to decode base64 response body and extract error information from
     * common error fields (error, message, detail) or returns the entire response body.
     * 
     * @param responseNode JSON node containing response data
     * @param objectMapper Jackson ObjectMapper for JSON processing
     * @param context Azure Functions execution context for logging
     * @return Extracted error message string, or empty string if extraction fails
     */
    private String extractErrorMessage(JsonNode responseNode, ObjectMapper objectMapper, ExecutionContext context) {
        try {
            String responseBodyEncoded = responseNode.path("body").asText("");
            if (!responseBodyEncoded.isEmpty()) {
                try {
                    String decodedBody = new String(Base64.getDecoder().decode(responseBodyEncoded));
                    JsonNode bodyNode = objectMapper.readTree(decodedBody);
                    
                    // Try to extract error message from common error fields
                    if (bodyNode.has("error") && !bodyNode.path("error").isNull()) {
                        return bodyNode.path("error").asText();
                    } else if (bodyNode.has("message") && !bodyNode.path("message").isNull()) {
                        return bodyNode.path("message").asText();
                    } else if (bodyNode.has("detail") && !bodyNode.path("detail").isNull()) {
                        return bodyNode.path("detail").asText();
                    } else {
                        // Return the entire decoded body as string if no specific error field found
                        return decodedBody;
                    }
                } catch (Exception e) {
                    context.getLogger().warning("Could not decode response body for error extraction: " + e.getMessage());
                    // Return the encoded body as fallback
                    return responseBodyEncoded;
                }
            }
            
            // Return empty string if no response body
            return "";
            
        } catch (Exception e) {
            context.getLogger().warning("Error extracting error message: " + e.getMessage());
            return "";
        }
    }
}