//! Typed error enum for `PluginRegistry` installation, mutation, and authorization operations.

use std::io;
use std::path::PathBuf;

use sideveil_protocol::plugin::ManifestValidationError;
use thiserror::Error;

/// Error returned by [`super::PluginRegistry`] operations.
#[derive(Debug, Error)]
pub enum PluginRegistryError {
    /// Target plugin identifier is not registered.
    #[error("plugin '{0}' not found")]
    PluginNotFound(String),
    /// Attempted to overwrite or uninstall the immutable built-in diagnostic plugin.
    #[error("cannot modify built-in plugin '{0}'")]
    BuiltinProtected(String),
    /// Manifest schema, ID, or network URL validation failed.
    #[error(transparent)]
    InvalidManifest(#[from] ManifestValidationError),
    /// Loopback Dev Mode server URL failed validation.
    #[error("{0}")]
    InvalidDevUrl(&'static str),
    /// Embed configuration parameter fell outside allowed bounds.
    #[error("invalid {field}: {reason}")]
    InvalidParameter {
        /// Camel-case JSON parameter name.
        field: &'static str,
        /// Expected range or constraint description.
        reason: &'static str,
    },
    /// Plugin installation source or package layout violated safety constraints.
    #[error("{0}")]
    InstallConstraint(String),
    /// Plugin backend attachment was rejected by manifest or trigger ACL policy.
    #[error("{0}")]
    BackendUnauthorized(String),
    /// JSON serialization or deserialization failed during registry operations.
    #[error("{context}: {source}")]
    Json {
        /// Operation context describing the failing JSON payload.
        context: String,
        /// Underlying `serde_json` error.
        #[source]
        source: serde_json::Error,
    },
    /// Filesystem I/O failed during staging, activation, removal, or persistence.
    #[error("{context}: {source}")]
    Io {
        /// Operation context describing the failing filesystem action.
        context: String,
        /// Underlying `std::io::Error`.
        #[source]
        source: io::Error,
    },
    /// Requested plugin directory is missing on disk.
    #[error("plugin '{0}' is not installed")]
    NotInstalled(String),
    /// Source directory for installation does not exist or is not a directory.
    #[error("source directory '{}' does not exist or is not a directory", .0.display())]
    InvalidSourceDir(PathBuf),
}
