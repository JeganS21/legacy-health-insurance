# LegacyHealthInsurance

Insurance documents land in a **Google Drive** folder → the server imports them **every 5 minutes** and extracts their text →
**Claude** (Claude Desktop, your subscription) reads them through **MCP** and extracts the fields defined in `workflow.yaml` →
the server **validates** Claude's answer, stores it in **MySQL** and appends a row to an **Excel** sheet. Every step is **logged**.

```
Google Drive folder
      │  every 5 min (@Scheduled, no AI)
      ▼
DriveSyncService ──► TextExtractor (PDFBox) ──► MySQL  insurance_documents (PENDING_EXTRACTION)
                                                   ▲
Claude Desktop ──MCP──► InsuranceTools             │
   1 getWorkflowInstructions   (workflow.yaml)     │
   2 listPendingDocuments                          │
   3 getDocumentText                               │
   4 saveExtractedFields ──► FieldValidator ──► ExtractionService ──► MySQL (EXTRACTED)
                                                                 └──► ExcelWriter ──► output/insurance_details.xlsx
   5 getIngestionLogs / syncDriveNow

Every step ──► AuditLogService ──► logs/insurance.log + audit_logs table ──► Dashboard (http://localhost:8081)
```

## Why MCP here

The server does the boring, reliable work (fetching, parsing, validating, writing files). Claude does the part that needs
understanding (reading a messy policy document and pulling out the right values). MCP is the contract between them:
Claude can only act through the tools we expose, and **the server validates everything before it is stored**.

## Setup

### 1. MySQL
Running locally, user `root` / password `root` (change in `src/main/resources/application.properties`).
Database `legacy_health_insurance` is created automatically.

### 2. Google Drive (service account)
1. https://console.cloud.google.com → create a project → **APIs & Services → Library → Google Drive API → Enable**.
2. **IAM & Admin → Service Accounts → Create service account** (no roles needed).
3. Open it → **Keys → Add key → JSON**. Save the file as `credentials/service-account.json` in this project.
   It is in `.gitignore` - **never commit it**.
4. Copy the service account's email (`...@...iam.gserviceaccount.com`).
5. In Google Drive, create a folder (e.g. `Insurance Inbox`) → **Share** → paste that email → **Viewer**.
6. Open the folder; copy the ID from the URL `https://drive.google.com/drive/folders/<FOLDER_ID>`
   into `insurance.drive.folder-id` in `application.properties`.

Supported files: PDF (with real text, not scanned images), TXT, Google Docs.

### 3. Run
```
.\mvnw.cmd spring-boot:run
```
Dashboard: http://localhost:8081 · MCP endpoint: http://localhost:8081/mcp

### 4. Connect Claude Desktop
Easiest: run `powershell -ExecutionPolicy Bypass -File .\scripts\add-claude-mcp.ps1`, then fully quit and reopen Claude Desktop.

Or add it by hand. Claude Desktop → **Settings → Developer → Edit Config** (`claude_desktop_config.json`), add:
```json
{
  "mcpServers": {
    "legacy-health-insurance": {
      "command": "npx",
      "args": ["mcp-remote", "http://localhost:8081/mcp"]
    }
  }
}
```
Restart Claude Desktop (needs Node.js for `npx`).

### 5. Run the workflow
Ask Claude:

> Use the legacy-health-insurance tools. Get the workflow instructions, then process every pending document:
> read its text, extract the fields exactly as instructed, and save them. Afterwards show me the latest logs
> and give me a short summary of each policy.

Open `output/insurance_details.xlsx` in Excel to see the rows (close it while Claude is saving - if it's open,
the server retries the write on the next sync).

### 6. Or press "Extract" on the dashboard (no chat needed)
The dashboard has an **Extract** button on every pending document and an **Extract all pending** button in the header.
The server asks Claude itself (`ClaudeExtractionService`) and saves the result through the same `ExtractionService`
the MCP tool uses, so the validation, MySQL, Excel and logs are identical. Choose how it reaches Claude with
`insurance.claude.mode` in `application.properties`:

- **`cli` (default)** - runs Claude Code headless (`claude -p`) signed in with your Claude subscription. No API key,
  no extra cost; usage counts against your plan limits. Install Claude Code, run `claude` once to sign in, then start
  the server from a new terminal. Log source `CLAUDE_CLI`.
- **`api`** - calls the Anthropic Messages API. Set `$env:ANTHROPIC_API_KEY = "sk-ant-..."` before starting the
  server. Log source `CLAUDE_API`. Use this for production.

## Changing the fields
Edit `workflow.yaml` (no restart needed). If you add/remove fields, rename or delete the old Excel file so it is
recreated with the new columns.

## Tests
```
.\mvnw.cmd test -Dtest=FieldValidatorTest
```

## Production notes
- Health insurance documents contain personal/health data: restrict the MCP endpoint (Spring Security/OAuth),
  encrypt the database, and use an AI provider covered by a data-processing agreement.
- Replace `ddl-auto=update` with Flyway migrations.
- Fully automatic extraction (no human in Claude Desktop) needs an Anthropic API key and a Spring AI `ChatClient`
  calling the same `ExtractionService`.
