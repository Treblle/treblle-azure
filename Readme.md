# Treblle Azure Integration

A complete Azure integration solution that captures API requests and responses from Azure API Management and forwards them to Treblle for monitoring and analytics.

## Architecture Overview

```
Azure API Management → Azure Event Hub → Azure Function App → Treblle API
```

This integration leverages Azure's native services to provide real-time API monitoring:
- **Azure API Management**: Captures API requests/responses using policies
- **Azure Event Hub**: Provides reliable message streaming and buffering
- **Azure Function App**: Processes events and forwards to Treblle with error handling and retry logic

## Prerequisites

- Azure subscription with appropriate permissions
- Treblle account with API key and SDK token
- Azure CLI or PowerShell (for deployment)
- Visual Studio Code with Azure Functions extension (recommended)

## Setup Guide

### Step 1: Create and Configure Event Hub

1. **Create Event Hub Namespace**
2. **Create Event Hub**
3. **Configure Access Policy**
   - Navigate to Event Hub → Settings → Shared access policies
   - Create a new policy with "Send" and "Listen" permissions
   - Copy the **Primary connection string** for later use
   - Format: `Endpoint=sb://<eventhub-name>.servicebus.windows.net/;SharedAccessKeyName=<policy-name>;SharedAccessKey=<access-key>`

### Step 2: Configure Azure API Management Logger

1. **Create Logger in APIM**
   - Follow [Microsoft's Event Hub logging guide](https://learn.microsoft.com/en-us/azure/api-management/api-management-howto-log-event-hubs?tabs=PowerShell)
2. **Note the logger name** (e.g., `treblle-logger`) for use in API policies

### Step 3: Configure API Policies

1. **Apply Policy to API**
   - Navigate to your API in APIM
   - Go to Design tab → Inbound processing → Code view
   - Add the policy content from `Policy-Content.xml`
   - Update the logger name to match your created logger

2. **Policy Locations**
   - **API Level**: Applies to specific API
   - **Global Level**: Applies to all APIs
   - **Operation Level**: Applies to specific operations

3. **Create and Activate Revision**
   - Go to Revisions tab
   - Create new revision with your policy changes
   - Set as current revision

### Step 4: Deploy Azure Function App

#### 4.1 Create Function App

```bash
az functionapp create \
  --resource-group <resource-group> \
  --consumption-plan-location <region> \
  --runtime java \
  --runtime-version 17 \
  --functions-version 4 \
  --name <function-app-name> \
  --storage-account <storage-account> \
  --os-type Windows
```

#### 4.2 Deploy Function Code

**Using VS Code:**
1. Install Azure Functions extension
2. Open project folder
3. Press F1 → "Azure Functions: Deploy to Function App"
4. Select your function app

**Using Azure CLI:**
```bash
func azure functionapp publish <function-app-name> --java
```

#### 4.3 Configure Application Settings

Set the following environment variables in your Function App:

| Setting | Description | Example |
|---------|-------------|---------|
| `AzureWebJobsStorage` | Storage account connection string | `DefaultEndpointsProtocol=https;AccountName=...` |
| `eventhubconnection` | Event Hub connection string from Step 1 | `Endpoint=sb://namespace.servicebus.windows.net/;...` |
| `eventhub` | Event Hub name | `treblle-events` |
| `consumergroup` | Event Hub consumer group | `$Default` |
| `TREBLLE_API_KEY` | Your Treblle project ID | `your-project-id` |
| `TREBLLE_SDK_TOKEN` | Your Treblle API key | `your-api-key` |
| `ADDITIONAL_MASK_KEYWORDS` | Additional fields to mask (comma-separated) | `Ocp-Apim-Subscription-Key,Authorization` |


## Configuration Reference

### Event Hub Settings
- **Message Retention**: 1 day (minimum)
- **Consumer Group**: Use `$Default` or create dedicated group

### Function App Settings
- **Hosting Plan**: Consumption (cost-effective for variable loads)
- **Runtime**: Java 17
- **OS**: Windows (recommended for Java Azure Functions)
- **Timeout**: 5 minutes (default)


### Common Issues

#### 1. Function Not Triggering
- Verify Event Hub connection string
- Check consumer group exists
- Ensure function app has proper permissions

#### 2. Treblle Publishing Failures
- Verify `TREBLLE_API_KEY` and `TREBLLE_SDK_TOKEN`
- Check function logs for HTTP error codes
- Ensure network connectivity to Treblle endpoints

#### 3. Memory or Timeout Issues
- Monitor function execution time
- Consider upgrading to Premium plan for consistent performance
- Optimize batch processing settings

### Debugging Commands

```bash
# Check function app status
az functionapp show --name <function-app-name> --resource-group <resource-group>

# View function logs
az webapp log tail --name <function-app-name> --resource-group <resource-group>

# Test Event Hub connectivity
az eventhubs eventhub show --resource-group <resource-group> --namespace-name <namespace> --name <eventhub>
```

