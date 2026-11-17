use super::i18n::tf;
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures_util::StreamExt;
use reqwest::header::{HeaderMap, HeaderValue, ACCEPT, AUTHORIZATION};
use reqwest::{Method, StatusCode};
use serde::de::DeserializeOwned;
use serde::Deserialize;
use serde_json::{json, Value};
use tokio::sync::mpsc;
use uuid::Uuid;

use crate::{display, DomainManifest, Session};
use harnex_host_runtime::session_auth::SessionCredential;


#[derive(Clone)]
pub struct ApiClient {
    endpoint: String,
    credentials: SessionCredential,
    manifest: DomainManifest,
    client: reqwest::Client,
    admissions: Arc<Mutex<BTreeMap<String, Value>>>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct WorkspaceView {
    pub workspace_id: String,
    pub instance_id: String,
    pub name: String,
    pub root: String,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct WorkspaceGitView {
    pub state: String,
    pub branch: Option<String>,
    pub commit: Option<String>,
}

impl WorkspaceGitView {
    pub fn label(&self) -> Option<String> {
        match self.state.as_str() {
            "branch" => self.branch.clone(),
            "detached" => self
                .commit
                .as_ref()
                .map(|commit| format!("HEAD | {commit}")),
            "unavailable" => Some(super::i18n::text("unavailable").into()),
            _ => None,
        }
    }

    pub fn unavailable() -> Self {
        Self {
            state: "unavailable".into(),
            ..Self::default()
        }
    }
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ThreadStateView {
    pub permission_mode: String,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ThreadSummary {
    #[serde(rename = "conversationId")]
    pub id: String,
    pub title: String,
    pub updated_at: u64,
    pub model: Option<String>,
    pub provider: Option<String>,
    pub toolsets: Vec<String>,
    pub state: ThreadStateView,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct MessageView {
    pub message_id: String,
    pub role: String,
    pub content: String,
    pub original_input: Option<String>,
    pub reasoning: Option<String>,
    pub reasoning_block: bool,
    pub tool_error: Option<bool>,
    pub channel: Option<String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ThreadView {
    pub id: String,
    pub title: String,
    pub model: Option<String>,
    pub provider: Option<String>,
    pub reasoning_effort: Option<String>,
    pub toolsets: Vec<String>,
    pub state: ThreadStateView,
    pub messages: Vec<MessageView>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ConversationMeta {
    pub conversation_id: String,
    pub title: String,
    pub defaults: Value,
    pub history_revision: u64,
    pub active_leaf_id: Option<String>,
}
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
struct SummaryPage {
    items: Vec<ThreadSummary>,
}
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct MessageRecord {
    pub message_id: String,
    pub parent_message_id: Option<String>,
    pub role: String,
    pub status: String,
    pub content_parts: Vec<Value>,
}
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct MessagePage {
    items: Vec<MessageRecord>,
    next_cursor: Option<String>,
}
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct PresentationView {
    pub run: RunView,
    pub cursor: PresentationCursor,
    pub history: RunHistory,
    pub live: LiveView,
    pub interactions: Vec<InteractionView>,
    pub inputs: Vec<RunInputView>,
}
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct PresentationCursor {
    pub projection_seq: u64,
    pub stream_epoch: Option<u64>,
    pub replay_available: bool,
}
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct RunHistory {
    pub conversation: Option<ConversationMeta>,
    pub answer: Option<MessageRecord>,
}
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct LiveView {
    pub text: String,
    pub thinking: String,
    pub tools: Vec<Value>,
    pub messages: Vec<Value>,
}
#[derive(Clone, Debug)]
pub struct PresentationBundle {
    pub snapshot: PresentationView,
    pub thread: ThreadView,
}
impl ThreadView {
    fn from_archive_page(
        meta: ConversationMeta,
        mut messages: Vec<MessageRecord>,
        answer: Option<MessageRecord>,
    ) -> Self {
        messages.reverse();
        if let Some(answer) = answer {
            if !messages
                .iter()
                .any(|item| item.message_id == answer.message_id)
            {
                messages.push(answer);
            }
        }
        Self::from_archive(meta, messages)
    }
    fn from_archive(meta: ConversationMeta, messages: Vec<MessageRecord>) -> Self {
        Self {
            id: meta.conversation_id,
            title: meta.title,
            provider: meta.defaults["text"]["provider"].as_str().map(String::from),
            model: meta.defaults["text"]["model"].as_str().map(String::from),
            reasoning_effort: meta.defaults["text"]["reasoningEffort"]
                .as_str()
                .map(String::from),
            state: ThreadStateView {
                permission_mode: meta.defaults["permissionMode"]
                    .as_str()
                    .unwrap_or("ask")
                    .to_string(),
            },
            toolsets: meta.defaults["toolsets"]
                .as_array()
                .map(|items| {
                    items
                        .iter()
                        .filter_map(Value::as_str)
                        .map(String::from)
                        .collect()
                })
                .unwrap_or_default(),
            messages: messages
                .into_iter()
                .map(|message| MessageView {
                    message_id: message.message_id,
                    role: message.role.to_ascii_lowercase(),
                    content: message
                        .content_parts
                        .iter()
                        .map(|part| match part["type"].as_str() {
                            Some("TEXT") => part["text"].as_str().unwrap_or("").to_string(),
                            Some("ARTIFACT_REF") => tf!(
                                "[Attachment {}]",
                                "[附件 {}]",
                                part["artifactId"].as_str().unwrap_or("")
                            ),
                            Some("IMAGE_OPERATION_REF") => tf!(
                                "[Image operation {}]",
                                "[图像操作 {}]",
                                part["imageOperationId"].as_str().unwrap_or("")
                            ),
                            _ => String::new(),
                        })
                        .collect::<Vec<_>>()
                        .join(""),
                    ..Default::default()
                })
                .collect(),
            ..Default::default()
        }
    }
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct UsageView {
    pub prompt: u64,
    pub completion: u64,
    pub total: u64,
    pub cached_input: u64,
    pub cache_write_input: u64,
    pub reasoning: u64,
    pub complete: bool,
}

impl UsageView {
    pub fn accumulate(&mut self, other: &Self) {
        self.prompt = self.prompt.saturating_add(other.prompt);
        self.completion = self.completion.saturating_add(other.completion);
        self.total = self.total.saturating_add(if other.total > 0 {
            other.total
        } else {
            other.prompt.saturating_add(other.completion)
        });
        self.cached_input = self.cached_input.saturating_add(other.cached_input);
        self.cache_write_input = self
            .cache_write_input
            .saturating_add(other.cache_write_input);
        self.reasoning = self.reasoning.saturating_add(other.reasoning);
        self.complete = false; // A completed model call is not a completed Run.
    }

    pub fn cache_hit_rate(&self) -> Option<f64> {
        (self.prompt > 0 && self.cached_input > 0)
            .then(|| self.cached_input as f64 / self.prompt as f64 * 100.0)
    }
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct RunView {
    pub run_id: String,
    #[serde(rename = "conversationId")]
    pub thread_id: String,
    pub goal_id: Option<String>,
    pub revision: u64,
    pub status: String,
    pub actual_provider: Option<String>,
    pub actual_model: Option<String>,
    pub requested_provider: Option<String>,
    pub requested_model: Option<String>,
    pub usage: Option<UsageView>,
    pub last_seq: u64,
    pub turn_id: Option<String>,
    pub execution_spec: Value,
    pub owns_conversation: bool,
    pub executing: bool,
    pub terminal: bool,
    pub pending_input_count: u64,
}

impl RunView {
    pub fn is_live(&self) -> bool {
        !self.terminal
            && !matches!(
                self.status.as_str(),
                "COMPLETED" | "INCOMPLETE" | "FAILED" | "CANCELLED" | "EXPIRED"
            )
    }
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
pub struct RunListView {
    pub runs: Vec<RunView>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct InteractionView {
    pub kind: String,
    #[serde(rename = "conversationId")]
    pub thread_id: String,
    pub call_id: String,
    pub created_at: u64,
    pub payload: Value,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
pub struct InteractionListView {
    pub interactions: Vec<InteractionView>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ChangeEntry {
    pub path: String,
    pub status: String,
    pub git_status: Option<String>,
    pub diff_available: bool,
    pub added: u64,
    pub removed: u64,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ChangesView {
    pub run_id: String,
    pub entries: Vec<ChangeEntry>,
    pub repository_type: String,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct DiffView {
    pub run_id: String,
    pub path: String,
    pub available: bool,
    pub binary: bool,
    pub truncated: bool,
    pub unified_diff: String,
    pub added: u64,
    pub removed: u64,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct FileSearchHit {
    pub path: String,
    pub name: String,
    pub snippet: Option<String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct FileSearchPage {
    pub query: String,
    pub entries: Vec<FileSearchHit>,
    pub truncated_scan: bool,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct ModelCatalog {
    providers_detailed: Vec<ModelProvider>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct ModelProvider {
    name: String,
    label: String,
    models: Vec<ModelDefinition>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct ModelDefinition {
    id: String,
    label: String,
    description: Option<String>,
    default: bool,
    reasoning_efforts: Vec<String>,
    default_reasoning_effort: String,
    function_tool_reasoning_effort: Option<String>,
}

#[derive(Clone, Debug, Default)]
pub struct ModelOption {
    pub provider: String,
    pub provider_label: String,
    pub model: String,
    pub label: String,
    pub description: Option<String>,
    pub default: bool,
    pub reasoning_efforts: Vec<String>,
    pub default_reasoning_effort: String,
    pub function_tool_reasoning_effort: Option<String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct RunInputView {
    pub input_id: String,
    pub run_id: String,
    pub sequence: u64,
    pub revision: u64,
    pub mode: String,
    pub text: String,
    pub status: String,
    pub created_at: u64,
    pub terminal_reason: Option<String>,
}

impl RunInputView {
    pub fn withdrawable(&self) -> bool {
        matches!(self.status.as_str(), "QUEUED" | "HELD")
    }
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
struct RunInputListView {
    inputs: Vec<RunInputView>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct SnapshotView {
    pub seq: u64,
    pub label: String,
    pub kind: String,
    pub run_id: Option<String>,
    pub taken_at: String,
    pub conversation_restorable: bool,
    pub file_restorable: bool,
    pub file_coverage: String,
    pub msg_count: u64,
    pub warnings: Vec<String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct RestoreFilePlan {
    pub path: String,
    pub action: String,
    pub conflict: bool,
    pub reason: Option<String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct RestorePlanView {
    pub history_revision: Option<u64>,
    pub seq: u64,
    pub mode: String,
    pub conversation_restorable: bool,
    pub can_restore_files: bool,
    pub file_coverage: String,
    pub warnings: Vec<String>,
    pub files: Vec<RestoreFilePlan>,
    pub conflicts: Vec<RestoreFilePlan>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct GoalBudgetView {
    pub max_goal_turns: u64,
    pub max_model_calls: u64,
    pub max_tool_calls: u64,
    pub token_budget: u64,
    pub cost_budget: f64,
    pub active_wall_clock_budget_ms: u64,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct GoalView {
    pub goal_id: String,
    #[serde(rename = "conversationId")]
    pub thread_id: String,
    pub revision: u64,
    pub objective: String,
    pub status: String,
    pub active_run_id: Option<String>,
    pub latest_run_status: Option<String>,
    pub budget: GoalBudgetView,
    pub run_ids: Vec<String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
struct GoalListView {
    goals: Vec<GoalView>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct TodoView {
    pub content: String,
    pub status: String,
    pub active_form: Option<String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct SkillView {
    pub name: String,
    pub description: String,
    pub source: String,
    pub enabled: bool,
    pub effective: bool,
}

#[derive(Clone, Debug, Default)]
pub struct ToolsetOption {
    pub name: String,
    pub tools: Vec<String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct CommandView {
    pub name: String,
    pub description: String,
    pub arg_hint: String,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct StreamEvent {
    #[serde(rename = "type")]
    pub kind: String,
    pub run_id: Option<String>,
    #[serde(rename = "conversationId")]
    pub thread_id: Option<String>,
    pub seq: u64,
    pub stream_epoch: Option<u64>,
    pub ts: Option<u64>,
    pub data: Value,
}

#[derive(Clone, Debug)]
pub enum StreamClose {
    Eof {
        after_seq: u64,
        stream_epoch: Option<u64>,
    },
    Refresh,
    ReplayGone,
    RunNotFound,
}

#[derive(Debug)]
pub struct ApiError {
    pub message: String,
}

impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for ApiError {}

impl ApiClient {
    pub fn new(session: &Session) -> Result<Self, String> {
        let client = reqwest::Client::builder()
            .no_proxy()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(35 * 60))
            .build()
            .map_err(display)?;
        Ok(Self {
            endpoint: session.endpoint.clone(),
            credentials: session.credentials.clone(),
            manifest: session.manifest.clone(),
            client,
            admissions: Arc::new(Mutex::new(BTreeMap::new())),
        })
    }

    fn request(&self, method: Method, path: &str) -> Result<reqwest::RequestBuilder, ApiError> {
        let mut headers = HeaderMap::new();
        headers.insert(
            "X-Harnex-Scope-Id",
            HeaderValue::from_str(&self.manifest.scope_id).map_err(api_display)?,
        );
        headers.insert(
            "X-Harnex-Instance-Id",
            HeaderValue::from_str(&self.manifest.instance_id).map_err(api_display)?,
        );
        headers.insert(
            ACCEPT,
            HeaderValue::from_static("application/json, text/event-stream"),
        );
        Ok(self
            .client
            .request(method, format!("{}{}", self.endpoint, path))
            .headers(headers))
    }

    async fn session_token(&self, rejected: Option<String>) -> Result<String, ApiError> {
        let credentials = self.credentials.clone();
        tokio::task::spawn_blocking(move || credentials.token(rejected.as_deref()))
            .await
            .map_err(api_display)?
            .map_err(api_display)
    }

    async fn send(&self, request: reqwest::RequestBuilder) -> Result<reqwest::Response, ApiError> {
        let token = self.session_token(None).await?;
        let mut request = request.build().map_err(api_display)?;
        request.headers_mut().insert(
            AUTHORIZATION,
            HeaderValue::from_str(&format!("Bearer {token}")).map_err(api_display)?,
        );
        let retry = request.try_clone();
        let response = self.client.execute(request).await.map_err(api_display)?;
        self.validate_identity(&response)?;
        if response.status() == StatusCode::UNAUTHORIZED {
            let renewed = self.session_token(Some(token.clone())).await?;
            if renewed != token {
                if let Some(mut retry) = retry {
                    // Authentication rejected the first attempt before dispatch.
                    // Keep the exact body/clientRequestId and never retry a second 401.
                    retry.headers_mut().insert(
                        AUTHORIZATION,
                        HeaderValue::from_str(&format!("Bearer {renewed}")).map_err(api_display)?,
                    );
                    let response = self.client.execute(retry).await.map_err(api_display)?;
                    self.validate_identity(&response)?;
                    return Ok(response);
                }
            }
        }
        Ok(response)
    }

    fn validate_identity(&self, response: &reqwest::Response) -> Result<(), ApiError> {
        let workspace = response
            .headers()
            .get("X-Harnex-Scope-Id")
            .and_then(|value| value.to_str().ok());
        let instance = response
            .headers()
            .get("X-Harnex-Instance-Id")
            .and_then(|value| value.to_str().ok());
        if workspace != Some(self.manifest.scope_id.as_str())
            || instance != Some(self.manifest.instance_id.as_str())
        {
            return Err(ApiError {
                message: "Runtime identity changed while TUI was attached".to_string(),
            });
        }
        Ok(())
    }

    async fn decode<T: DeserializeOwned>(
        &self,
        response: reqwest::Response,
    ) -> Result<T, ApiError> {
        self.validate_identity(&response)?;
        let status = response.status();
        let body = response.text().await.map_err(api_display)?;
        if !status.is_success() {
            return Err(http_error(status, &body));
        }
        serde_json::from_str(&body).map_err(api_display)
    }

    pub async fn preview_url(&self, source: &str) -> Result<String, ApiError> {
        let request = if let Some(id) = source.strip_prefix("artifact:") {
            json!({"artifactId": id})
        } else {
            json!({"path": source})
        };
        let view: Value = self.json(Method::POST, "/api/v1/previews", request).await?;
        let path = view["url"]
            .as_str()
            .filter(|path| path.starts_with("/api/v1/preview-content/"))
            .ok_or_else(|| api_display("Invalid preview URL"))?;
        Ok(format!("{}{}", self.endpoint.trim_end_matches('/'), path))
    }

    async fn get<T: DeserializeOwned>(&self, path: &str) -> Result<T, ApiError> {
        let response = self.send(self.request(Method::GET, path)?).await?;
        self.decode(response).await
    }

    async fn json<T: DeserializeOwned>(
        &self,
        method: Method,
        path: &str,
        body: Value,
    ) -> Result<T, ApiError> {
        let response = self.send(self.request(method, path)?.json(&body)).await?;
        self.decode(response).await
    }

    pub async fn agent_available(&self) -> Result<bool, ApiError> {
        let protocol: Value = self.get("/api/v1/protocol").await?;
        Ok(protocol["capabilities"]
            .as_array()
            .is_some_and(|items| items.iter().any(|item| item == "agent.execution")))
    }

    pub async fn workspace(&self) -> Result<WorkspaceView, ApiError> {
        let scope: Value = self.get("/api/v1/scope").await?;
        let root = scope["projectRoot"].as_str().unwrap_or("").to_string();
        let name = if scope["runtimeProfile"] == "PERSONAL" {
            "Personal".into()
        } else {
            std::path::Path::new(&root)
                .file_name()
                .map(|name| name.to_string_lossy().into_owned())
                .unwrap_or_else(|| root.clone())
        };
        Ok(WorkspaceView {
            workspace_id: scope["scopeId"].as_str().unwrap_or("").into(),
            instance_id: scope["instanceId"].as_str().unwrap_or("").into(),
            name,
            root,
        })
    }

    pub async fn threads(&self) -> Result<Vec<ThreadSummary>, ApiError> {
        let page: SummaryPage = self.get("/api/v1/conversations?limit=100").await?;
        Ok(page.items)
    }

    pub async fn workspace_git(&self) -> Result<WorkspaceGitView, ApiError> {
        let response = self
            .send(
                self.request(Method::GET, "/api/v1/workspace/git")?
                    .timeout(Duration::from_secs(8)),
            )
            .await?;
        self.decode(response).await
    }

    pub async fn thread(&self, thread_id: &str) -> Result<ThreadView, ApiError> {
        let meta: ConversationMeta = self
            .get(&format!("/api/v1/conversations/{}", component(thread_id)))
            .await?;
        let page: MessagePage = self
            .get(&format!(
                "/api/v1/conversations/{}/messages?limit=100",
                component(thread_id)
            ))
            .await?;
        Ok(ThreadView::from_archive_page(meta, page.items, None))
    }

    pub async fn export_conversation(
        &self,
        thread_id: &str,
        title: &str,
    ) -> Result<String, ApiError> {
        let meta: ConversationMeta = self
            .get(&format!("/api/v1/conversations/{}", component(thread_id)))
            .await?;
        let mut messages = Vec::new();
        let mut cursor: Option<String> = None;
        let mut seen = std::collections::HashSet::new();
        let mut bytes = 0_usize;
        loop {
            let mut path = format!(
                "/api/v1/conversations/{}/messages?limit=100",
                component(thread_id)
            );
            if let Some(leaf) = &meta.active_leaf_id {
                path.push_str(&format!("&leafId={}", component(leaf)));
            }
            if let Some(cursor) = &cursor {
                path.push_str(&format!("&cursor={}", component(cursor)));
            }
            let page: MessagePage = self.get(&path).await?;
            for message in page.items {
                bytes += message
                    .content_parts
                    .iter()
                    .map(|part| part["text"].as_str().unwrap_or("").len())
                    .sum::<usize>();
                messages.push(message);
            }
            if messages.len() > 100_000 || bytes > 128 * 1024 * 1024 {
                return Err(api_display(
                    "Conversation is too large to export in one file",
                ));
            }
            match page.next_cursor.filter(|value| !value.is_empty()) {
                Some(next) if seen.insert(next.clone()) => cursor = Some(next),
                Some(_) => return Err(api_display("History pagination did not advance")),
                None => break,
            }
        }
        messages.reverse();
        let heading = if meta.title.is_empty() {
            title
        } else {
            &meta.title
        };
        let mut output = format!("# {}\n\n", heading.replace(['\r', '\n'], " "));
        for message in messages {
            output.push_str(&format!(
                "## {}\n\n",
                if message.role.eq_ignore_ascii_case("user") {
                    "User"
                } else {
                    "Harnex"
                }
            ));
            for part in message.content_parts {
                match part["type"].as_str() {
                    Some("TEXT") => output.push_str(part["text"].as_str().unwrap_or("")),
                    Some("ARTIFACT_REF") => output.push_str(&format!(
                        "[Attachment: {}]",
                        part["artifactId"].as_str().unwrap_or("")
                    )),
                    Some("IMAGE_OPERATION_REF") => output.push_str(&format!(
                        "[Image operation: {}]",
                        part["imageOperationId"].as_str().unwrap_or("")
                    )),
                    _ => {}
                }
                output.push('\n');
            }
            output.push('\n');
        }
        Ok(output)
    }

    pub async fn presentation(&self, run_id: &str) -> Result<PresentationBundle, ApiError> {
        let snapshot: PresentationView = self
            .get(&format!("/api/v1/runs/{}/presentation", component(run_id)))
            .await?;
        let conversation = snapshot
            .history
            .conversation
            .clone()
            .ok_or_else(|| api_display("Presentation omitted conversation"))?;
        let messages = match snapshot.history.answer.as_ref() {
            Some(answer) if answer.parent_message_id.is_none() => Vec::new(),
            answer => {
                let mut path = format!(
                    "/api/v1/conversations/{}/messages?limit=100",
                    component(&snapshot.run.thread_id)
                );
                if let Some(leaf) = answer.and_then(|item| item.parent_message_id.as_ref()) {
                    path.push_str(&format!("&leafId={}", component(leaf)));
                }
                self.get::<MessagePage>(&path).await?.items
            }
        };
        let mut answer = snapshot.history.answer.clone();
        if let Some(answer) = answer.as_mut() {
            if snapshot.run.executing && matches!(answer.status.as_str(), "PENDING" | "STREAMING") {
                answer.content_parts.clear();
            }
        }
        let thread = ThreadView::from_archive_page(conversation, messages, answer);
        Ok(PresentationBundle { snapshot, thread })
    }

    pub async fn runs(&self, thread_id: &str) -> Result<Vec<RunView>, ApiError> {
        let view: RunListView = self
            .get(&format!(
                "/api/v1/runs?conversationId={}",
                component(thread_id)
            ))
            .await?;
        Ok(view.runs)
    }

    pub async fn run(&self, run_id: &str) -> Result<RunView, ApiError> {
        self.get(&format!("/api/v1/runs/{}", component(run_id)))
            .await
    }

    pub async fn next_submission(&self, source: &RunView) -> Result<Option<Value>, ApiError> {
        let queue: Value = self
            .get(&format!(
                "/api/v1/conversations/{}/submissions",
                component(&source.thread_id)
            ))
            .await?;
        let Some(items) = queue["items"].as_array() else {
            return Ok(None);
        };
        let source_submission = items
            .iter()
            .find(|item| item["runId"].as_str() == Some(source.run_id.as_str()))
            .and_then(|item| item["submissionId"].as_str());
        Ok(items
            .iter()
            .filter(|item| match item["predecessorSubmissionId"].as_str() {
                Some(id) => Some(id) == source_submission,
                None => item["predecessorTurnId"].as_str() == source.turn_id.as_deref(),
            })
            .min_by_key(|item| item["ordinal"].as_u64().unwrap_or(0))
            .cloned())
    }

    pub async fn create_run(
        &self,
        thread_id: Option<&str>,
        input: &str,
        permission_mode: &str,
        provider: Option<&str>,
        model: Option<&str>,
        reasoning_effort: &str,
        toolsets: &[String],
    ) -> Result<RunView, ApiError> {
        let mut intent = json!({ "conversationId": thread_id, "input": input,
            "execution": { "kind": "AGENT", "text": { "provider": provider, "model": model, "reasoningEffort": reasoning_effort },
                "interactionTarget": "local-api", "permissionMode": permission_mode, "toolsets": toolsets },
            "channel": "tui", "stream": false });
        if self.manifest.runtime_profile == "PERSONAL" {
            intent["execution"] = json!({"kind":"CONVERSATION","action":"AUTO","text":{"provider":provider,"model":model,"reasoningEffort":reasoning_effort}});
        }
        let fingerprint = intent.to_string();
        let cached = self.admissions.lock().unwrap().get(&fingerprint).cloned();
        let body = if let Some(value) = cached {
            value
        } else {
            let mut value = intent;
            value["clientRequestId"] = json!(format!("tui-{}", Uuid::new_v4()));
            if let Some(id) = thread_id {
                let meta: ConversationMeta = self
                    .get(&format!("/api/v1/conversations/{}", component(id)))
                    .await?;
                value["expectedHistoryRevision"] = json!(meta.history_revision);
                value["parentMessageId"] = json!(meta.active_leaf_id);
            }
            self.admissions
                .lock()
                .unwrap()
                .insert(fingerprint.clone(), value.clone());
            value
        };
        let response = self
            .send(self.request(Method::POST, "/api/v1/runs")?.json(&body))
            .await?;
        // A rejected admission can be rebuilt against the latest history. Keep
        // the exact request only when acceptance is unknown (transport/5xx).
        if response.status().is_client_error() {
            self.admissions.lock().unwrap().remove(&fingerprint);
        }
        let result = self.decode(response).await;
        if result.is_ok() {
            self.admissions.lock().unwrap().remove(&fingerprint);
        }
        result
    }

    pub async fn cancel_run(&self, run_id: &str) -> Result<Value, ApiError> {
        let run = self.run(run_id).await?;
        if run.goal_id.is_some() {
            return self.json(Method::POST, &format!("/api/v1/runs/{}/cancel", component(run_id)), json!({
                "revision": run.revision, "clientRequestId": format!("tui-cancel-{}", Uuid::new_v4())
            })).await;
        }
        self.json(
            Method::POST,
            &format!("/api/v1/runs/{}/cancel", component(run_id)),
            json!({}),
        )
        .await
    }

    pub async fn enqueue_input(
        &self,
        run_id: &str,
        mode: &str,
        text: &str,
    ) -> Result<Value, ApiError> {
        if mode == "QUEUE" {
            let run = self.run(run_id).await?;
            let intent = json!({ "input": text, "predecessorTurnId": run.turn_id,
                "execution": run.execution_spec, "channel": "tui" });
            let fingerprint = format!("queue:{run_id}:{}", intent);
            let body = {
                let mut cache = self.admissions.lock().unwrap();
                cache
                    .entry(fingerprint.clone())
                    .or_insert_with(|| {
                        let mut body = intent;
                        body["clientRequestId"] = json!(format!("tui-queue-{}", Uuid::new_v4()));
                        body
                    })
                    .clone()
            };
            let result = self
                .json(
                    Method::POST,
                    &format!(
                        "/api/v1/conversations/{}/submissions",
                        component(&run.thread_id)
                    ),
                    body,
                )
                .await;
            if result.is_ok() {
                self.admissions.lock().unwrap().remove(&fingerprint);
            }
            return result;
        }
        self.json(Method::POST, &format!("/api/v1/runs/{}/inputs", component(run_id)), json!({
            "mode": "STEER", "text": text, "channel": "tui", "clientRequestId": format!("tui-input-{}", Uuid::new_v4())
        })).await
    }

    pub async fn set_permission_mode(
        &self,
        thread_id: &str,
        mode: &str,
    ) -> Result<Value, ApiError> {
        let response = self
            .send(self.request(
                Method::PUT,
                &format!(
                    "/api/v1/agent/conversations/{}/mode?mode={}",
                    component(thread_id),
                    component(mode)
                ),
            )?)
            .await?;
        self.decode(response).await
    }

    pub async fn interactions(&self, thread_id: &str) -> Result<Vec<InteractionView>, ApiError> {
        if self.manifest.runtime_profile == "PERSONAL" {
            return Ok(Vec::new());
        }
        let view: InteractionListView = self
            .get(&format!(
                "/api/v1/agent/conversations/{}/interactions?status=pending",
                component(thread_id)
            ))
            .await?;
        Ok(view.interactions)
    }

    pub async fn resolve_interaction(
        &self,
        interaction: &InteractionView,
        resolution: Value,
    ) -> Result<Value, ApiError> {
        self.json(
            Method::POST,
            &format!(
                "/api/v1/agent/conversations/{}/interactions/{}",
                component(&interaction.thread_id),
                component(&interaction.call_id)
            ),
            resolution,
        )
        .await
    }

    pub async fn changes(&self, run_id: &str) -> Result<ChangesView, ApiError> {
        self.get(&format!("/api/v1/changes?runId={}", component(run_id)))
            .await
    }

    pub async fn diff(&self, run_id: &str, path: &str) -> Result<DiffView, ApiError> {
        self.get(&format!(
            "/api/v1/changes/diff?runId={}&path={}",
            component(run_id),
            component(path)
        ))
        .await
    }

    pub async fn search_files(&self, query: &str) -> Result<FileSearchPage, ApiError> {
        self.get(&format!(
            "/api/v1/files/search?q={}&content=false&limit=50",
            component(query)
        ))
        .await
    }

    pub async fn models(&self) -> Result<Vec<ModelOption>, ApiError> {
        let catalog: ModelCatalog = self.get("/api/v1/models").await?;
        Ok(catalog
            .providers_detailed
            .into_iter()
            .flat_map(|provider| {
                provider.models.into_iter().map(move |model| ModelOption {
                    provider: provider.name.clone(),
                    provider_label: provider.label.clone(),
                    model: model.id,
                    label: model.label,
                    description: model.description,
                    default: model.default,
                    reasoning_efforts: model.reasoning_efforts,
                    default_reasoning_effort: model.default_reasoning_effort,
                    function_tool_reasoning_effort: model.function_tool_reasoning_effort,
                })
            })
            .collect())
    }

    pub async fn run_inputs(&self, run_id: &str) -> Result<Vec<RunInputView>, ApiError> {
        let run = self.run(run_id).await?;
        let mut items = if run.execution_spec["kind"] == "AGENT" {
            self.get::<RunInputListView>(&format!("/api/v1/runs/{}/inputs", component(run_id)))
                .await?
                .inputs
        } else {
            Vec::new()
        };
        let queue: Value = self
            .get(&format!(
                "/api/v1/conversations/{}/submissions",
                component(&run.thread_id)
            ))
            .await?;
        for queued in queue["items"].as_array().into_iter().flatten() {
            items.push(RunInputView {
                input_id: queued["submissionId"].as_str().unwrap_or("").to_string(),
                run_id: run_id.to_string(),
                sequence: queued["ordinal"].as_u64().unwrap_or(0),
                revision: queued["revision"].as_u64().unwrap_or(0),
                mode: "QUEUE".to_string(),
                text: queued["request"]["input"]
                    .as_str()
                    .unwrap_or("")
                    .to_string(),
                status: queued["state"].as_str().unwrap_or("").to_string(),
                created_at: queued["createdAt"].as_u64().unwrap_or(0),
                terminal_reason: queued["reason"].as_str().map(String::from),
            });
        }
        Ok(items)
    }

    pub async fn withdraw_input(&self, run_id: &str, input_id: &str) -> Result<Value, ApiError> {
        if self
            .run_inputs(run_id)
            .await?
            .iter()
            .any(|item| item.input_id == input_id && item.mode == "QUEUE")
        {
            let run = self.run(run_id).await?;
            return self
                .json(
                    Method::POST,
                    &format!(
                        "/api/v1/conversations/{}/submissions/{}/cancel",
                        component(&run.thread_id),
                        component(input_id)
                    ),
                    json!({}),
                )
                .await;
        }
        let response = self
            .send(self.request(
                Method::DELETE,
                &format!(
                    "/api/v1/runs/{}/inputs/{}?clientRequestId={}",
                    component(run_id),
                    component(input_id),
                    component(&format!("tui-withdraw-{}", Uuid::new_v4()))
                ),
            )?)
            .await?;
        self.decode(response).await
    }

    pub async fn snapshots(&self, thread_id: &str) -> Result<Vec<SnapshotView>, ApiError> {
        self.get(&format!(
            "/api/v1/agent/conversations/{}/snapshots",
            component(thread_id)
        ))
        .await
    }

    pub async fn restore_plan(
        &self,
        thread_id: &str,
        seq: u64,
        mode: &str,
    ) -> Result<RestorePlanView, ApiError> {
        self.json(
            Method::POST,
            &format!(
                "/api/v1/agent/conversations/{}/snapshots/{seq}/restore-plan",
                component(thread_id)
            ),
            json!({"mode": mode, "paths": Value::Null}),
        )
        .await
    }

    pub async fn restore_snapshot(
        &self,
        thread_id: &str,
        seq: u64,
        mode: &str,
        history_revision: Option<u64>,
        command_id: &str,
    ) -> Result<Value, ApiError> {
        self.json(
            Method::POST,
            &format!(
                "/api/v1/agent/conversations/{}/snapshots/{seq}/restore",
                component(thread_id)
            ),
            json!({"mode": mode, "force": false, "paths": Value::Null, "expectedHistoryRevision": history_revision, "commandId": command_id}),
        )
        .await
    }

    pub async fn branch_snapshot(&self, thread_id: &str, seq: u64) -> Result<Value, ApiError> {
        self.json(
            Method::POST,
            &format!(
                "/api/v1/agent/conversations/{}/snapshots/{seq}/branch",
                component(thread_id)
            ),
            json!({"commandId": uuid::Uuid::new_v4().to_string()}),
        )
        .await
    }

    pub async fn goals(&self, thread_id: &str) -> Result<Vec<GoalView>, ApiError> {
        if self.manifest.runtime_profile == "PERSONAL" {
            return Ok(Vec::new());
        }
        let view: GoalListView = self
            .get(&format!(
                "/api/v1/goals?conversationId={}",
                component(thread_id)
            ))
            .await?;
        Ok(view.goals)
    }

    pub async fn goal_control(&self, run: &RunView, action: &str) -> Result<RunView, ApiError> {
        let latest = self.run(&run.run_id).await?;
        self.json(
            Method::POST,
            &format!(
                "/api/v1/runs/{}/{}",
                component(&run.run_id),
                component(action)
            ),
            json!({
                "revision": latest.revision,
                "clientRequestId": format!("tui-goal-{}", Uuid::new_v4()),
                "interactionTarget": if action == "resume" { Some("local-api") } else { None }
            }),
        )
        .await
    }

    pub async fn review_goal(
        &self,
        run: &RunView,
        decision: &str,
        feedback: Option<&str>,
    ) -> Result<RunView, ApiError> {
        let latest = self.run(&run.run_id).await?;
        self.json(
            Method::POST,
            &format!("/api/v1/runs/{}/review", component(&run.run_id)),
            json!({
                "revision": latest.revision,
                "decision": decision,
                "feedback": feedback,
                "clientRequestId": format!("tui-goal-review-{}", Uuid::new_v4())
            }),
        )
        .await
    }

    pub async fn todos(&self, thread_id: &str) -> Result<Vec<TodoView>, ApiError> {
        self.get(&format!(
            "/api/v1/agent/conversations/{}/todos",
            component(thread_id)
        ))
        .await
    }

    pub async fn skills(&self) -> Result<Vec<SkillView>, ApiError> {
        if self.manifest.runtime_profile == "PERSONAL" {
            return Ok(Vec::new());
        }
        self.get("/api/v1/skills").await
    }

    pub async fn toolsets(&self) -> Result<Vec<ToolsetOption>, ApiError> {
        if self.manifest.runtime_profile == "PERSONAL" {
            return Ok(Vec::new());
        }
        let presets: BTreeMap<String, Vec<String>> = self.get("/api/v1/toolsets").await?;
        Ok(presets
            .into_iter()
            .map(|(name, tools)| ToolsetOption { name, tools })
            .collect())
    }

    pub async fn commands(&self) -> Result<Vec<CommandView>, ApiError> {
        self.get("/api/v1/commands").await
    }

    pub async fn stream_events(
        &self,
        run_id: &str,
        after_seq: u64,
        stream_epoch: Option<u64>,
        sender: mpsc::Sender<StreamEvent>,
    ) -> Result<StreamClose, ApiError> {
        let mut path = format!(
            "/api/v1/runs/{}/events?afterSeq={after_seq}",
            component(run_id)
        );
        if let Some(epoch) = stream_epoch {
            path.push_str(&format!("&streamEpoch={epoch}"));
        }
        let response = self.send(self.request(Method::GET, &path)?).await?;
        self.validate_identity(&response)?;
        match response.status() {
            StatusCode::CONFLICT => {
                let reset: Value = response.json().await.map_err(api_display)?;
                if reset["error"]["code"].as_str() != Some("replay_reset") {
                    return Err(api_display(reset));
                }
                return Ok(StreamClose::Refresh);
            }
            StatusCode::GONE => return Ok(StreamClose::ReplayGone),
            StatusCode::NOT_FOUND => return Ok(StreamClose::RunNotFound),
            status if !status.is_success() => {
                let body = response.text().await.map_err(api_display)?;
                return Err(http_error(status, &body));
            }
            _ => {}
        }

        let mut bytes = Vec::new();
        let mut latest_seq = after_seq;
        let mut latest_epoch = stream_epoch;
        let mut stream = response.bytes_stream();
        while let Some(chunk) = stream.next().await {
            bytes.extend_from_slice(&chunk.map_err(api_display)?);
            while let Some((index, delimiter)) = frame_boundary(&bytes) {
                let frame = bytes[..index].to_vec();
                bytes.drain(..index + delimiter);
                if let Some(event) = parse_frame(&frame)? {
                    if matches!(
                        event.kind.as_str(),
                        "message_committed" | "interaction_updated" | "agent_step_committed"
                    ) || event.kind == "notice"
                        && event.data["kind"].as_str() == Some("buffer_miss")
                        || latest_epoch.is_some() && event.stream_epoch != latest_epoch
                    {
                        return Ok(StreamClose::Refresh);
                    }
                    if event.seq <= latest_seq {
                        continue;
                    }
                    if matches!(
                        event.kind.as_str(),
                        "approval_required" | "plan_review" | "user_question"
                    ) {
                        continue;
                    }
                    let stop = matches!(event.kind.as_str(), "done" | "cancelled")
                        || event.kind == "goal_state_changed"
                            && matches!(
                                event.data["status"].as_str(),
                                Some(
                                    "PAUSED"
                                        | "BLOCKED"
                                        | "BUDGET_LIMITED"
                                        | "READY_FOR_REVIEW"
                                        | "INTERRUPTED"
                                )
                            );
                    latest_seq = latest_seq.max(event.seq);
                    latest_epoch = event.stream_epoch.or(latest_epoch);
                    if sender.send(event).await.is_err() || stop {
                        return Ok(StreamClose::Eof {
                            after_seq: latest_seq,
                            stream_epoch: latest_epoch,
                        });
                    }
                }
            }
        }
        if !bytes.is_empty() {
            if let Some(event) = parse_frame(&bytes)? {
                latest_seq = latest_seq.max(event.seq);
                latest_epoch = event.stream_epoch.or(latest_epoch);
                let _ = sender.send(event).await;
            }
        }
        Ok(StreamClose::Eof {
            after_seq: latest_seq,
            stream_epoch: latest_epoch,
        })
    }
}

fn frame_boundary(bytes: &[u8]) -> Option<(usize, usize)> {
    let lf = bytes.windows(2).position(|window| window == b"\n\n");
    let crlf = bytes.windows(4).position(|window| window == b"\r\n\r\n");
    match (lf, crlf) {
        (Some(a), Some(b)) if a <= b => Some((a, 2)),
        (Some(_), Some(b)) => Some((b, 4)),
        (Some(a), None) => Some((a, 2)),
        (None, Some(b)) => Some((b, 4)),
        (None, None) => None,
    }
}

fn parse_frame(frame: &[u8]) -> Result<Option<StreamEvent>, ApiError> {
    let text = std::str::from_utf8(frame).map_err(api_display)?;
    let data = text
        .lines()
        .filter_map(|line| {
            line.trim_end_matches('\r')
                .strip_prefix("data:")
                .map(str::trim_start)
        })
        .collect::<Vec<_>>()
        .join("\n");
    if data.is_empty() {
        return Ok(None);
    }
    serde_json::from_str(&data).map(Some).map_err(api_display)
}

fn component(value: &str) -> String {
    url::form_urlencoded::byte_serialize(value.as_bytes()).collect()
}

fn api_display(error: impl std::fmt::Display) -> ApiError {
    ApiError {
        message: super::i18n::text(&error.to_string()).to_string(),
    }
}

fn http_error(status: StatusCode, body: &str) -> ApiError {
    let message = serde_json::from_str::<Value>(body)
        .ok()
        .and_then(|value| {
            value
                .pointer("/error/message")
                .and_then(Value::as_str)
                .map(ToString::to_string)
        })
        .unwrap_or_else(|| body.trim().to_string());
    ApiError {
        message: tf!(
            "Runtime HTTP {status}: {message}",
            "运行时 HTTP {status}：{message}"
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn model_catalog_and_thread_defaults_keep_reasoning_capabilities() {
        let catalog: ModelCatalog = serde_json::from_value(json!({"providersDetailed":[{"name":"custom","models":[{"id":"model","default":true,"reasoningEfforts":["none","high"],"defaultReasoningEffort":"high","functionToolReasoningEffort":"none"}]}]})).unwrap();
        let model = &catalog.providers_detailed[0].models[0];
        assert!(model.default);
        assert_eq!(model.reasoning_efforts, ["none", "high"]);
        assert_eq!(model.default_reasoning_effort, "high");
        assert_eq!(
            model.function_tool_reasoning_effort.as_deref(),
            Some("none")
        );
        let meta = serde_json::from_value(json!({"conversationId":"thread","defaults":{"text":{"provider":"custom","model":"model","reasoningEffort":"high"}}})).unwrap();
        let thread = ThreadView::from_archive(meta, vec![]);
        assert_eq!(thread.reasoning_effort.as_deref(), Some("high"));
    }
    #[test]
    fn java_history_page_renders_in_chronological_order() {
        let samples: Value =
            serde_json::from_str(include_str!("../../../packages/protocol/fixtures.json")).unwrap();
        let page: MessagePage = serde_json::from_value(samples["history_page"].clone()).unwrap();
        let thread = ThreadView::from_archive_page(ConversationMeta::default(), page.items, None);
        let content: Vec<_> = thread
            .messages
            .iter()
            .map(|message| message.content.as_str())
            .collect();
        assert_eq!(
            content,
            vec![
                "first question",
                "first answer",
                "second question",
                "second answer"
            ]
        );
    }

    #[test]
    fn java_contract_samples_decode_without_legacy_identity_aliases() {
        let samples: Value =
            serde_json::from_str(include_str!("../../../packages/protocol/fixtures.json")).unwrap();
        for (name, sample) in samples.as_object().unwrap() {
            if name.starts_with("run_") {
                let run: RunView = serde_json::from_value(sample.clone()).unwrap();
                assert_eq!(run.thread_id, "conversation_fixture", "{name}");
                assert_eq!(run.last_seq, 15, "{name}");
                assert!(matches!(
                    run.execution_spec["kind"].as_str(),
                    Some("AGENT" | "CONVERSATION")
                ));
                assert_eq!(run.owns_conversation, !run.terminal, "{name}");
                assert!(sample.get("threadId").is_none());
            }
        }
        let event: StreamEvent = serde_json::from_value(samples["event_text"].clone()).unwrap();
        assert_eq!(event.thread_id.as_deref(), Some("conversation_fixture"));
        assert_eq!(event.stream_epoch, Some(3));
        assert_eq!(
            samples["request_image_edit"]["execution"]["image"]["sourceArtifactIds"][0],
            "artifact_source"
        );
    }

    #[test]
    fn parser_handles_crlf_and_multiline_frames() {
        let payload = b"event: message\r\ndata: {\"type\":\"text_delta\",\r\ndata: \"seq\":2,\"data\":{\"text\":\"hi\"}}\r\n\r\n";
        let (index, delimiter) = frame_boundary(payload).expect("frame boundary");
        assert_eq!(delimiter, 4);
        let event = parse_frame(&payload[..index]).unwrap().unwrap();
        assert_eq!(event.kind, "text_delta");
        assert_eq!(event.seq, 2);
        assert_eq!(event.data["text"], "hi");
    }

    #[test]
    fn parser_ignores_heartbeat_frames() {
        assert!(parse_frame(b": heartbeat").unwrap().is_none());
    }

    #[test]
    fn advanced_projection_dtos_accept_additive_fields() {
        let input: RunInputView = serde_json::from_value(json!({
            "inputId":"input-1","runId":"run-1","sequence":1,"revision":2,
            "mode":"STEER","text":"focus tests","status":"QUEUED","createdAt":1,
            "futureField":true
        }))
        .unwrap();
        assert!(input.withdrawable());
        let snapshot: SnapshotView = serde_json::from_value(json!({
            "seq":3,"label":"pre-tool","kind":"pre_tool","takenAt":"now",
            "conversationRestorable":true,"fileRestorable":true,"fileCoverage":"complete"
        }))
        .unwrap();
        assert_eq!(snapshot.seq, 3);
        let goal: GoalView = serde_json::from_value(json!({
            "goalId":"goal-1","threadId":"thread-1","revision":4,
            "objective":"finish tui","status":"RUNNING",
            "budget":{"maxGoalTurns":10,"tokenBudget":10000}
        }))
        .unwrap();
        assert_eq!(goal.budget.token_budget, 10_000);
    }
}
