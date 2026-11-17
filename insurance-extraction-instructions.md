# LegacyHealthInsurance – Field Extraction Instructions

These instructions tell Claude how to process insurance documents waiting in the
LegacyHealthInsurance pipeline (MCP server `legacy-health-insurance`).

## Steps

1. **Get the workflow.** Call `getWorkflowInstructions` first. The field list and
   rules it returns are the source of truth. If they differ from this file, follow the server.
2. **Optional sync.** Call `syncDriveNow` to pick up any new files in the Google Drive folder.
3. **List pending documents.** Call `listPendingDocuments`. If none are pending, stop
   and report "No pending documents".
4. **For each pending document:**
   1. Call `getDocumentText(documentId)`.
   2. Extract every workflow field using the rules below.
   3. Call `saveExtractedFields(documentId, fieldsJson)`.
   4. If the response starts with `REJECTED`, fix the listed problems and save again
      (at most 2 retries). If it is still rejected, leave the document and report the error.
5. **Check the logs.** Call `getIngestionLogs(limit=30)` and look for ERROR lines.
6. **Report.** For each document: documentId, file name, policy number, holder, insurer,
   saved/rejected, and a one-line summary. List any errors and duplicates.

## Fields

| Field | Type | Required | Description |
|---|---|---|---|
| policyHolderName | text | yes | Full name of the insured person / policy holder |
| policyNumber | text | yes | Policy or member number |
| insurer | text | yes | Insurance company name |
| planName | text | no | Name or type of plan (e.g. Gold PPO, Family Floater) |
| sumInsured | number | no | Total coverage amount / sum insured |
| premiumAmount | number | no | Premium amount per billing period |
| deductible | number | no | Deductible amount |
| coPayPercent | number | no | Co-pay percentage |
| startDate | date | no | Policy start date |
| expiryDate | date | no | Policy end / renewal date |
| keyExclusions | text | no | Short comma-separated list of main exclusions |
| summary | text | yes | 2–3 sentence plain-English summary of the policy |

## Extraction rules

- Copy values exactly as they appear in the document. Do not guess.
- If a value is not in the document, use `null`. Never make one up.
- **Dates:** `YYYY-MM-DD`. Convert formats like `01-Apr-2026` → `2026-04-01`.
  A "Policy Period A to B" gives `startDate = A` and `expiryDate = B`.
- **Amounts:** plain numbers with no currency symbols, commas or words.
  Indian grouping counts too: `Rs. 10,00,000` → `1000000`.
- **policyHolderName:** drop labels like "(fictional)" or "(Self)".
- **coPayPercent with conditions** (e.g. "10% for members aged 60+, otherwise 0%"):
  save the highest percentage that applies (`10`) and explain the condition in `summary`.
- **keyExclusions:** shorten each exclusion bullet to a few words, separated by commas.
- **summary:** 2–3 sentences covering who is insured, the sum insured, premium,
  deductible, main benefits, and any conditional terms.
- `fieldsJson` must be a single valid JSON object whose keys match the field names exactly.

## Duplicates

If two pending documents have the same file name and the same text, or extract to the same
`policyNumber`, process each one as normal. Then list them under "Possible duplicates" in the
report so a person can decide whether to delete the extra Excel row.
