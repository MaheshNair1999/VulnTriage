# VulnTriage

**AI-Assisted Vulnerability Triage and Prioritisation System**

A desktop application that combines static analysis (Semgrep, Trivy, Gitleaks, CodeQL, SonarQube) with
LLM-assisted triage (via Ollama) to reduce manual review effort for security findings.
Supports multiple independent project databases, configurable scan workflows,
and evaluation metrics comparing LLM verdicts against manual ground truth.

Built with Java 17, JavaFX 21, SQLite, and Ollama.

---

## Quick Start — Windows (No Java Required)

Download **VulnTriage.zip** from this repository, extract it, and run `VulnTriage.exe` directly.
The zip is a self-contained Windows app bundle — Java, JavaFX, and all dependencies are already included.
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
git clone <repo-url>
cd vulntriage

# 2. Pull the LLM model
ollama pull qwen2.5:3b

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
Projects are fully independent — each has its own repositories, findings, reviews, and triage runs.
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

### Step 6 — Triage (LLM)
Click **Triage**, configure Ollama URL and model, click **Run LLM Triage**.
Results are saved as an evaluation run and shown immediately in the results table.
Double-click any row to see the full reasoning, remediation suggestion, and code snippet.
Triage supports crash-resume — re-running skips already-triaged findings.

### Step 7 — Workflows (optional)
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
{ "type": "triage", "model": "qwen2.5:3b", "run_name": "My Run" }
{ "type": "score" }
{ "type": "report" }
```

### Step 8 — Evaluate
Click **Evaluate**, select a run, click **Load Results**.
View the confusion matrix (rows = manual verdict, columns = LLM verdict) and six key metrics.
Use the **scanner dropdown** to break down TP/FP/REVIEW distributions and LLM agreement by individual scanner.
Export results as JSON or CSV.

---

## Design Patterns

| Pattern | Where used |
|---------|-----------|
| Strategy | `TriageStrategy` — swap LLM backend without changing pipeline |
| Adapter | `ScannerAdapter` — uniform interface for all five scanners |
| Factory | `ScannerFactory`, `WorkflowStepFactory` — create correct adapter/stage from config |
| Repository | `FindingRepository` etc. — abstracts all DB access |
| Chain of Responsibility | `PipelineStage` — scan → filter → sample → triage → score → report |
| Observer | `PipelineObserver` — progress events to logger and UI |
| Singleton | `AppContext`, `SQLiteConnection` — single shared instance per project session |

---

## Project Structure

```
src/main/java/com/vulntriage/
├── app/          Application entry point and AppContext
├── config/       AppConfig, ScanConfig
├── domain/       Entity classes and enums
├── evaluation/   ConfusionMatrix, MetricsCalculator, EvaluationReport
├── event/        Observer pattern (PipelineEvent, PipelineObserver)
├── export/       JsonExporter, CsvExporter
├── filter/       Filter expression parser and rule engine
├── normaliser/   FindingNormaliser, DuplicateDetector
├── pipeline/     PipelineOrchestrator, WorkflowParser, WorkflowStepFactory, stage chain
├── repository/   Repository interfaces + SQLite implementations
├── sampling/     StratifiedSampler
├── scanner/      ScannerAdapter, SemgrepAdapter, TrivyAdapter, GitleaksAdapter, CodeQLAdapter, SonarQubeAdapter, ScannerFactory
├── triage/       TriageStrategy, OllamaTriageStrategy, MockTriageStrategy
└── ui/           JavaFX screens
    ├── dashboard/    Summary statistics with per-scanner breakdown
    ├── findings/     Filterable findings browser with double-click popup
    ├── repository/   Repository management
    ├── review/       Manual review with scanner badge (keyboard-driven)
    ├── triage/       LLM triage runner
    ├── workflow/     Workflow builder and runner
    ├── evaluation/   Metrics, confusion matrix, and per-scanner breakdown
    └── settings/     Ollama and application settings
```

---

## Running Tests

```bash
mvn test
```

~90 tests covering all backend layers. UI is tested manually by running the app.

---

## Author

**Mahesh Nair** — Cybersecurity Internship 2026

Supervisors: Prof. Francesco La Rosa · Prof. Pierluigi Dell'Acqua
