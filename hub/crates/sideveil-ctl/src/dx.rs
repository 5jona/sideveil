//! Offline developer-experience commands for `sideveil-ctl`: `validate`, `pack`, and `init`.

use std::fs;
use std::path::{Path, PathBuf};

use serde_json::{Value, json};
use sideveil_protocol::plugin::{PluginBackendKind, PluginManifest, validate_plugin_id};
use sideveil_protocol::svp::{is_archive_path, pack_plugin_dir_to_svp, read_svp_file};
use sideveil_protocol::{MANIFEST_SCHEMA_VERSION, PLUGIN_API_VERSION};

/// Starter template kind for `sideveil-ctl plugins init`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum PluginTemplate {
    Vanilla,
    React,
    UdsNative,
}

impl PluginTemplate {
    pub(crate) fn parse(raw: &str) -> Result<Self, String> {
        match raw.trim().to_ascii_lowercase().as_str() {
            "vanilla" => Ok(Self::Vanilla),
            "react" => Ok(Self::React),
            "uds-native" => Ok(Self::UdsNative),
            other => Err(format!(
                "Unknown template '{other}' (expected vanilla, react, or uds-native)"
            )),
        }
    }

    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::Vanilla => "vanilla",
            Self::React => "react",
            Self::UdsNative => "uds-native",
        }
    }
}

pub(crate) fn run_validate(target: &Path) -> Result<String, String> {
    let (manifest, warnings, file_count) = if is_archive_path(target) {
        let archive =
            read_svp_file(target).map_err(|e| format!("Archive validation failed: {e}"))?;
        let count = archive.files.len();
        (archive.manifest, archive.warnings, Some(count))
    } else if target.is_dir() {
        let (m, w) = validate_dir(target)?;
        (m, w, None)
    } else {
        return Err(format!(
            "Target '{}' is neither a directory nor a .svp/.zip archive",
            target.display()
        ));
    };

    let caps: Vec<Value> = manifest
        .effective_capabilities()
        .iter()
        .map(|cap| {
            json!({
                "capability": cap,
                "risk": cap.risk_tier(),
                "requiresApproval": cap.requires_explicit_grant(),
            })
        })
        .collect();
    let has_daemon =
        manifest.daemon.is_some() && manifest.effective_backend() == PluginBackendKind::UdsNative;

    let report = json!({
        "ok": true,
        "pluginId": manifest.id,
        "name": manifest.name,
        "version": manifest.version,
        "schemaVersion": manifest.effective_schema_version(),
        "apiVersion": manifest.effective_api_version(),
        "backend": manifest.effective_backend(),
        "hasDaemon": has_daemon,
        "fileCount": file_count,
        "capabilities": caps,
        "warnings": warnings,
    });
    serde_json::to_string(&report).map_err(|e| format!("Failed encoding report: {e}"))
}

pub(crate) fn run_pack(source_dir: &Path, output_path: Option<&Path>) -> Result<String, String> {
    let (manifest, warnings) = validate_dir(source_dir)?;
    let out = match output_path {
        Some(p) => p.to_path_buf(),
        None => {
            let parent = source_dir.parent().unwrap_or_else(|| Path::new("."));
            parent.join(format!("{}-{}.svp", manifest.id, manifest.version))
        }
    };
    let packed = pack_plugin_dir_to_svp(source_dir, &out)
        .map_err(|e| format!("Failed to pack .svp archive: {e}"))?;
    let bytes = fs::metadata(&out).map(|m| m.len()).unwrap_or(0);
    let report = json!({
        "ok": true,
        "pluginId": packed.id,
        "version": packed.version,
        "output": out.display().to_string(),
        "bytes": bytes,
        "warnings": warnings,
    });
    serde_json::to_string(&report).map_err(|e| format!("Failed encoding report: {e}"))
}

pub(crate) fn run_init(
    plugin_id: &str,
    template: PluginTemplate,
    output_dir: Option<&Path>,
) -> Result<String, String> {
    let id = plugin_id.trim();
    validate_plugin_id(id).map_err(|e| format!("Invalid plugin ID: {e}"))?;
    if matches!(
        id,
        "sideveil.diagnostic" | "sideveil.host" | "sideveil.hud.demo"
    ) {
        return Err(format!(
            "Plugin ID '{id}' is reserved for SideVeil internals"
        ));
    }
    let dir = output_dir.map_or_else(|| PathBuf::from(id), Path::to_path_buf);
    if dir.exists() {
        return Err(format!(
            "Target directory '{}' already exists",
            dir.display()
        ));
    }
    fs::create_dir_all(dir.join("web"))
        .map_err(|e| format!("Failed creating '{}': {e}", dir.display()))?;

    let manifest_json = build_template_manifest(id, template);
    fs::write(dir.join("manifest.json"), manifest_json)
        .map_err(|e| format!("Failed writing manifest.json: {e}"))?;
    fs::write(
        dir.join("web/index.html"),
        build_template_html(id, template),
    )
    .map_err(|e| format!("Failed writing web/index.html: {e}"))?;

    match template {
        PluginTemplate::Vanilla => {}
        PluginTemplate::React => {
            fs::create_dir_all(dir.join("src"))
                .map_err(|e| format!("Failed creating src dir: {e}"))?;
            fs::write(dir.join("package.json"), build_react_package_json(id))
                .map_err(|e| format!("Failed writing package.json: {e}"))?;
            fs::write(dir.join("src/App.tsx"), build_react_app_tsx(id))
                .map_err(|e| format!("Failed writing src/App.tsx: {e}"))?;
        }
        PluginTemplate::UdsNative => {
            fs::create_dir_all(dir.join("src"))
                .map_err(|e| format!("Failed creating src dir: {e}"))?;
            fs::write(dir.join("Cargo.toml"), build_native_cargo_toml(id))
                .map_err(|e| format!("Failed writing Cargo.toml: {e}"))?;
            fs::write(dir.join("src/main.rs"), build_native_main_rs(id))
                .map_err(|e| format!("Failed writing src/main.rs: {e}"))?;
        }
    }

    let report = json!({
        "ok": true,
        "pluginId": id,
        "template": template.as_str(),
        "directory": dir.display().to_string(),
    });
    serde_json::to_string(&report).map_err(|e| format!("Failed encoding report: {e}"))
}

fn validate_dir(dir: &Path) -> Result<(PluginManifest, Vec<String>), String> {
    let manifest_path = dir.join("manifest.json");
    let raw = fs::read_to_string(&manifest_path)
        .map_err(|e| format!("Failed to read '{}': {e}", manifest_path.display()))?;
    let manifest: PluginManifest =
        serde_json::from_str(&raw).map_err(|e| format!("Invalid manifest.json: {e}"))?;
    manifest
        .validate()
        .map_err(|e| format!("Manifest validation failed: {e}"))?;
    let mut warnings = Vec::new();
    if manifest.embed.is_none() {
        let entry = manifest.effective_entry();
        if !dir.join(entry).is_file() {
            return Err(format!("Manifest entry '{entry}' is missing"));
        }
        if !entry.starts_with("web/") {
            warnings.push(format!("Entry '{entry}' is outside 'web/'"));
        }
    }
    if let Some(daemon) = &manifest.daemon
        && !dir.join(&daemon.binary).is_file()
    {
        return Err(format!(
            "Declared daemon binary '{}' is missing (compile and place it before packing)",
            daemon.binary
        ));
    }
    Ok((manifest, warnings))
}

fn build_template_manifest(id: &str, template: PluginTemplate) -> String {
    let mut value = json!({
        "schemaVersion": MANIFEST_SCHEMA_VERSION,
        "apiVersion": PLUGIN_API_VERSION,
        "id": id,
        "name": id,
        "version": "0.1.0",
        "description": format!("SideVeil {} plugin", template.as_str()),
        "defaultEnabled": true,
        "entry": "web/index.html",
        "backend": if template == PluginTemplate::UdsNative { "uds-native" } else { "none" },
        "capabilities": ["toast"],
        "triggers": { "global": true, "priority": 20 },
        "surface": { "backdrop": "native-glass", "presentation": "drawer" }
    });
    if template == PluginTemplate::UdsNative
        && let Some(obj) = value.as_object_mut()
    {
        obj.insert(
            "daemon".to_owned(),
            json!({
                "binary": "bin/plugin-daemon",
                "restartPolicy": "on-failure",
                "maxRestarts": 5,
                "backoffMs": 1000
            }),
        );
    }
    format!(
        "{}\n",
        serde_json::to_string_pretty(&value).unwrap_or_default()
    )
}

fn build_template_html(id: &str, template: PluginTemplate) -> String {
    format!(
        "<!doctype html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"utf-8\" />\n  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\" />\n  <title>{id}</title>\n</head>\n<body style=\"font-family:system-ui;padding:16px;color:#e5e7eb;background:transparent\">\n  <h2>{id}</h2>\n  <p>Template: {}</p>\n  <button id=\"toast\">Show Toast</button>\n  <script>\n    document.getElementById('toast').onclick = () => window.SideVeil?.ui?.toast?.('Hello from {id}');\n  </script>\n</body>\n</html>\n",
        template.as_str()
    )
}

fn build_react_package_json(id: &str) -> String {
    format!(
        "{{\n  \"name\": \"{id}\",\n  \"private\": true,\n  \"version\": \"0.1.0\",\n  \"type\": \"module\",\n  \"scripts\": {{ \"build\": \"vite build --outDir web\" }},\n  \"dependencies\": {{ \"react\": \"^19.0.0\", \"react-dom\": \"^19.0.0\" }},\n  \"devDependencies\": {{ \"vite\": \"^8.0.0\", \"@vitejs/plugin-react\": \"^6.0.0\" }}\n}}\n"
    )
}

fn build_react_app_tsx(id: &str) -> String {
    format!(
        "export function App() {{\n  return (\n    <main style={{{{ padding: 16 }}}}>\n      <h2>{id}</h2>\n      <button onClick={{() => (window as any).SideVeil?.ui?.toast?.('Hello from {id}')}}>\n        Show Toast\n      </button>\n    </main>\n  );\n}}\n"
    )
}

fn build_native_cargo_toml(id: &str) -> String {
    let crate_name = id.replace('.', "-");
    let tag = concat!("v", env!("CARGO_PKG_VERSION"));
    format!(
        "[package]\nname = \"{crate_name}\"\nversion = \"0.1.0\"\nedition = \"2024\"\n\n[dependencies]\nserde_json = \"1\"\nsideveil-plugin-sdk = {{ git = \"https://github.com/5jona/sideveil.git\", tag = \"{tag}\" }}\nsideveil-protocol = {{ git = \"https://github.com/5jona/sideveil.git\", tag = \"{tag}\" }}\ntokio = {{ version = \"1\", features = [\"rt-multi-thread\", \"macros\", \"signal\"] }}\n"
    )
}

fn build_native_main_rs(id: &str) -> String {
    format!(
        "use serde_json::{{Value, json}};\nuse sideveil_plugin_sdk::{{\n    CancellationToken, PluginBackendConfig, PluginBackendRunner, PluginRpcContext,\n    PluginRpcError, PluginRpcHandler,\n}};\nuse sideveil_protocol::rpc::RpcRequest;\n\nstruct Handler;\n\nimpl PluginRpcHandler for Handler {{\n    async fn handle_rpc(\n        &self,\n        req: RpcRequest,\n        _ctx: PluginRpcContext,\n    ) -> Result<Value, PluginRpcError> {{\n        match req.method.as_str() {{\n            \"ping\" => Ok(json!({{ \"pong\": true, \"pluginId\": \"{id}\" }})),\n            other => Err(PluginRpcError::method_not_found(other)),\n        }}\n    }}\n}}\n\n#[tokio::main]\nasync fn main() {{\n    let cfg = PluginBackendConfig::from_env().unwrap_or_else(|_| PluginBackendConfig::new(\"{id}\"));\n    let cancel = CancellationToken::new();\n    let (_handle, runner) = PluginBackendRunner::new(cfg, cancel).expect(\"valid config\");\n    let _ = runner.run_with_signals(Handler).await;\n}}\n"
    )
}
