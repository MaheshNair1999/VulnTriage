# VulnTriage

**AI-Assisted Vulnerability Triage and Prioritisation System**

A desktop application that combines static analysis (Semgrep, Trivy, Gitleaks, CodeQL, SonarQube) with
LLM-assisted triage (via Ollama) to reduce manual review effort for security findings.
Supports multiple independent project databases, configurable scan workflows, versioned prompt templates,
and evaluation metrics comparing LLM verdicts against manual ground truth across multiple prompt versions.

Built with Java 17, JavaFX 21, SQLite, and Ollama.

---

## Quick Start — Windows (No Java Required)

Download **VulnTriage.zip** from this repository and extract it.
Inside the extracted folder, double-click **VulnTriage.vbs** to launch the application.
(`VulnTriage-run.bat` is the underlying launcher used by the VBS — keep both files in the same folder.)

The bundle is self-contained — Java, JavaFX, and all dependencies are included.
You only need **Ollama** (for LLM triage) and the scanners you want to use (Semgrep, Trivy, etc.).

---

## Requirements

| Tool | Version | Install |
|------|---------|---------|
| Java | 17+ | https://adoptium.net |
| Maven | 3.8+ | https://maven.apache.org |
| Semgrep | Latest | `pip install semgrep` |
| Trivy | Latest | https://trivy.dev |
| Gitleaks | Latest | https://github.com/gitleaks/gitleaks |
| CodeQL | Latest | https://codeql.github.com |
| SonarQube | Latest | https://www.sonarsource.com/products/sonarqube |
| Ollama | Latest | https://ollama.ai |

Semgrep and Trivy are the default scanners. Gitleaks, CodeQL, and SonarQube are optional and used via workflows or direct scan config.

---

## Quick Start

```bash
# 1. Clone the repository
git clone https://github.com/MaheshNair1999/VulnTriage.git
cd VulnTriage

# 2. Pull the LLM model
ollama pull qwen3:8b

# 3. Start Ollama (in a separate terminal)
ollama serve

# 4. Run the application
mvn javafx:run
```

On first launch a project selector appears. Create a new project (`.db` file) or open an existing one. Projects are stored in the `projects/` folder by default.

---

## Usage

### Step 1 — Select or Create a Project
At startup, choose an existing `.db` file or click **+ New Project** to create one.
Projects are fully independent — each has its own repositories, findings, reviews, triage runs, and prompt templates.
Click the project badge in the sidebar at any time to switch projects.

### Step 2 — Add a Repository
Click **Repositories** → **Add Repository** → browse to a local code folder.
Press **Delete** with a repository selected to remove it.
Removing a repository hides it from the list but preserves all its findings.

### Step 3 — Scan
Select a repository and click **Scan Selected**, or use a **Workflow** to automate the full pipeline.
Semgrep runs the `p/security-audit` ruleset by default. Trivy scans dependencies for CVEs.
Findings are deduplicated via fingerprint — re-scanning the same repo won't create duplicates.

Supported scanners:

| Scanner | What it finds |
|---------|--------------|
| Semgrep | Static code vulnerabilities (SAST) |
| Trivy | Dependency CVEs and misconfiguration |
| Gitleaks | Hardcoded secrets and credentials |
| CodeQL | Deep semantic code analysis |
| SonarQube | Code quality and security issues (requires running SonarQube server) |

### Step 4 — Browse Findings
Click **Findings** to view all findings in a table.
Filter by scanner, severity, category, or repository using the dropdowns and search bar.
**Double-click** any row to open a popup with the full finding details, code snippet, and scanner badge.
Press **Delete** with a row selected to remove the finding.

### Step 5 — Review
Click **Review** to manually label findings one at a time.
The view resumes from the last unreviewed finding automatically.
A scanner badge (colour-coded by tool) is shown alongside the severity and category for each finding.
A `#N of M` counter in the top-right matches the `#` column in the Findings page for cross-reference.

| Key | Action |
|-----|--------|
| `T` | True Positive |
| `F` | False Positive |
| `R` | Needs Review |
| `←` | Go back |
| `→` | Skip |
| `Esc` | Leave notes field |

Type a number in the **# go to** field and press Enter to jump directly to that finding.

### Step 6 — Manage Prompt Templates
Click **Prompts** to create and manage versioned LLM triage prompts.
Each template has a name, version string (e.g. `v1.0`, `v2.0`), and a body supporting these placeholders:

| Placeholder | Replaced with |
|-------------|--------------|
| `{{rule_id}}` | Finding rule identifier |
| `{{severity}}` | Finding severity level |
| `{{file_path}}` | File path where the issue was detected |
| `{{line_number}}` | Line number of the flagged code |
| `{{message}}` | Scanner message / description |
| `{{code_snippet}}` | Code snippet around the flagged line |
| `{{source_context}}` | Extended file context window (30 header lines + 80 before/after) |

Navigating away with unsaved changes prompts to save or discard.
Switching between templates while editing correctly saves the current template, not the one being navigated to.

### Step 7 — Triage
Click **Triage** to run an LLM triage pass on a filtered subset of findings.

**Ollama Connection** — configure the URL and model at the top of the left panel and click **Check Connection** to verify.

**Filter Criteria** — narrow down findings by scanner, repository, rule pattern, and severity, then click **Preview Matches** to see the matched set before committing to a run.

**Triage Config** — select a prompt template, enter a run name, and optionally set a repos base path if the prompt uses `{{source_context}}`.

**Resume support** — triage is crash-safe and version-aware. If a run is interrupted and restarted with the same run name (and force re-triage unchecked), only findings not yet triaged with that prompt version are processed. Running v2 after v1 (or v1 after v2) resumes correctly for each version independently — results are stored and checked per finding per version.

**Force re-triage** — when checked, existing results for the selected prompt version are deleted before the run (other versions are unaffected).

Results appear in the right panel as they are processed. Double-click any row for full reasoning, remediation, and code snippet.

### Step 8 — Triage Results
Click **Triage Results** for a read-only view of all saved LLM results across every run and prompt version.
Filter by version or repository using the dropdowns. Double-click any row for full details.
Use this view to compare what different prompt versions decided for the same findings without re-running anything.

### Step 9 — Workflows (optional)
Click **Workflows** to define and run automated pipelines combining scan, filter, sample, triage, and report steps.
Each step is configurable (scanner type, ruleset, sample size, model, run name).
Steps can be reordered by dragging. The workflow indicator in the sidebar shows when a workflow is running.
Double-click the JSON preview panel to open an expanded view.

**Supported workflow step types:**

```json
{ "type": "scan",   "scanner": "semgrep",   "ruleset": "p/security-audit" }
{ "type": "scan",   "scanner": "trivy" }
{ "type": "scan",   "scanner": "gitleaks" }
{ "type": "scan",   "scanner": "codeql" }
{ "type": "scan",   "scanner": "sonarqube", "sonar_url": "http://localhost:9000", "sonar_token": "..." }
{ "type": "filter", "condition": "severity >= WARNING" }
{ "type": "sample", "size": "1000" }
{ "type": "triage", "model": "qwen3:8b", "run_name": "My Run" }
{ "type": "score" }
{ "type": "report" }
```

### Step 10 — Evaluate
Click **Evaluate**, select a run, click **Load Results**.

**Confusion matrix** — rows = manual verdict, columns = LLM verdict.

**Key metrics** — TP Recall, False Negative Rate, Overall Accuracy, TP Precision, FP Agreement, FP Rate, REVIEW Agreement.

**Version comparison** — when two or more prompt versions have results, a comparison table shows side-by-side metrics. When three or more versions exist, use the **Base** and **Compare to** pickers to select any pair and see the delta for TP Recall, FP Agreement, and TP Precision.

**Scanner breakdown** — use the scanner dropdown to see per-tool finding counts, LLM triaged counts per version, verdict distributions, and LLM agreement.

**Export as HTML** — generates a full report including cover page, key metrics, per-version evaluation, scanner breakdown, and an **All Findings** table with one column per prompt version. Each version cell shows the LLM verdict, a match/mismatch indicator against the manual verdict, and confidence. Rows with any version mismatch are highlighted.

**Export as JSON / CSV** — machine-readable export of all evaluation metrics and results.

---

## Design Patterns

| Pattern | Where used |
|---------|-----------|
| Strategy | `TriageStrategy` — swap LLM backend without changing pipeline |
| Adapter | `ScannerAdapter` — uniform interface for all five scanners |
| Factory | `ScannerFactory`, `WorkflowStepFactory` — create correct adapter/stage from config |
| Repository | `FindingRepository`, `LlmResultRepository`, `PromptTemplateRepository`, etc. — abstracts all DB access |
| Chain of Responsibility | `PipelineStage` — scan → filter → sample → triage → score → report |
| Observer | `PipelineObserver` — progress events to logger and UI |
| Singleton | `AppContext`, `SQLiteConnection` — single shared instance per project session |

---

## Project Structure

```
src/main/java/com/vulntriage/
├── app/          Application entry point and AppContext
├── config/       AppConfig, ScanConfig
├── domain/       Entity classes (Finding, LlmResult, PromptTemplate, EvaluationRun, ...) and enums
├── evaluation/   ConfusionMatrix, MetricsCalculator, EvaluationReport
├── event/        Observer pattern (PipelineEvent, PipelineObserver)
├── experiment/   Standalone experiment runners (context window study, etc.)
├── export/       HtmlReportExporter (multi-version), JsonExporter, CsvExporter
├── filter/       Filter expression parser and rule engine
├── normaliser/   FindingNormaliser, DuplicateDetector
├── pipeline/     PipelineOrchestrator, WorkflowParser, WorkflowStepFactory, stage chain
├── repository/   Repository interfaces + SQLite implementations
├── sampling/     StratifiedSampler
├── scanner/      ScannerAdapter, SemgrepAdapter, TrivyAdapter, GitleaksAdapter, CodeQLAdapter, SonarQubeAdapter, ScannerFactory
├── triage/       TriageStrategy, OllamaTriageStrategy, MockTriageStrategy, PromptBuilder
└── ui/           JavaFX screens
    ├── dashboard/    Summary statistics with per-scanner breakdown
    ├── findings/     Filterable findings browser with double-click popup
    ├── prompts/      Versioned prompt template editor with navigation guard
    ├── repository/   Repository management
    ├── review/       Manual review with scanner badge (keyboard-driven)
    ├── triage/       Triage (active runner) + Triage Results (read-only viewer)
    ├── workflow/     Workflow builder and runner
    ├── evaluation/   Metrics, confusion matrix, multi-version comparison, and per-scanner breakdown
    └── settings/     Ollama and application settings
```

---

## Running Tests

```bash
mvn test
```

Tests cover all backend layers: evaluation metrics, CVSS scoring, caching decorator, Command pattern/undo, parallel scan coordinator, pipeline integration, scanners, triage, filter rules, sampling, workflow, and repository. UI is tested manually by running the app.

---

## Author

**Mahesh Nair** — Cybersecurity MSc Thesis 2026

Supervisors: Prof. Francesco La Rosa · Prof. Pierluigi Dell'Acqua
