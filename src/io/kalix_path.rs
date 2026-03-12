use std::path::{Path, PathBuf};

/// The kind of path specified by the user.
#[derive(Debug, Clone, PartialEq)]
pub enum PathKind {
    /// Absolute path (starts with `/` on Unix or drive letter on Windows)
    Absolute,
    /// Relative path resolved against the context directory
    Relative,
    /// Trailhead path (starts with `^/`) — walks up ancestor directories to find the target
    Trailhead,
}

/// A Kalix path that preserves the original string for round-tripping and holds
/// the resolved absolute path after resolution.
#[derive(Debug, Clone)]
pub struct KalixPath {
    /// The original path string exactly as authored (e.g. `^/data/climate/evap.csv`)
    pub raw: String,
    /// The kind of path (Absolute, Relative, or Trailhead)
    pub kind: PathKind,
    /// The resolved absolute path (populated after calling `resolve`)
    pub resolved: PathBuf,
}

impl KalixPath {
    /// Parse a raw path string and determine its kind.
    /// This does not touch the filesystem — call `resolve` to produce the absolute path.
    pub fn parse(raw: &str) -> Result<KalixPath, String> {
        let kind = if raw.starts_with("^/") || raw.starts_with("^\\") {
            if raw.len() <= 2 {
                return Err(format!("Invalid trailhead path syntax: '{}'", raw));
            }
            PathKind::Trailhead
        } else if raw == "^" {
            return Err(format!("Invalid trailhead path syntax: '{}'", raw));
        } else if Path::new(raw).is_absolute() {
            PathKind::Absolute
        } else {
            PathKind::Relative
        };

        Ok(KalixPath {
            raw: raw.to_string(),
            kind,
            resolved: PathBuf::new(),
        })
    }

    /// For trailhead paths, returns the target portion (everything after `^/`).
    pub fn target(&self) -> Option<&str> {
        match self.kind {
            PathKind::Trailhead => Some(&self.raw[2..]),
            _ => None,
        }
    }

    /// Resolve the path against a context directory (typically the model file's parent directory).
    ///
    /// - **Absolute** paths are returned as-is.
    /// - **Relative** paths are joined with the context directory.
    /// - **Trailhead** paths walk upward from the context directory until the target is found.
    pub fn resolve(&mut self, context_dir: &Path) -> Result<&PathBuf, String> {
        self.resolved = match self.kind {
            PathKind::Absolute => {
                PathBuf::from(&self.raw)
            }
            PathKind::Relative => {
                context_dir.join(&self.raw)
            }
            PathKind::Trailhead => {
                let target = &self.raw[2..];
                resolve_trailhead(target, context_dir)?
            }
        };
        Ok(&self.resolved)
    }
}

/// Walk upward from `context_dir` looking for `target` (a relative path fragment).
/// Returns the first (nearest) match as an absolute path.
fn resolve_trailhead(target: &str, context_dir: &Path) -> Result<PathBuf, String> {
    let mut current = context_dir.to_path_buf();
    loop {
        let candidate = current.join(target);
        if candidate.exists() {
            return Ok(candidate);
        }
        match current.parent() {
            Some(parent) => {
                if parent == current {
                    // Reached filesystem root — nowhere left to search
                    break;
                }
                current = parent.to_path_buf();
            }
            None => break,
        }
    }
    Err(format!(
        "Trailhead path target '{}' not found in any ancestor of '{}'",
        target,
        context_dir.display()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    /// Create a unique temporary directory for a test
    fn make_test_dir(test_name: &str) -> PathBuf {
        let dir = std::env::temp_dir()
            .join("kalix_tests")
            .join(format!("{}_{}", test_name, uuid::Uuid::new_v4()));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// Clean up a test directory
    fn cleanup(dir: &Path) {
        let _ = fs::remove_dir_all(dir);
    }

    // ---- Parsing tests (no filesystem) ----

    #[test]
    fn parse_trailhead() {
        let kp = KalixPath::parse("^/data/climate/evap.csv").unwrap();
        assert_eq!(kp.kind, PathKind::Trailhead);
        assert_eq!(kp.target(), Some("data/climate/evap.csv"));
    }

    #[test]
    fn parse_relative() {
        let kp = KalixPath::parse("../data.csv").unwrap();
        assert_eq!(kp.kind, PathKind::Relative);
        assert_eq!(kp.target(), None);
    }

    #[test]
    fn parse_absolute() {
        let kp = KalixPath::parse("/home/user/data.csv").unwrap();
        assert_eq!(kp.kind, PathKind::Absolute);
    }

    #[test]
    fn parse_bare_caret_is_error() {
        assert!(KalixPath::parse("^").is_err());
    }

    #[test]
    fn parse_caret_slash_only_is_error() {
        assert!(KalixPath::parse("^/").is_err());
    }

    #[test]
    fn parse_caret_no_slash_is_relative() {
        let kp = KalixPath::parse("^something").unwrap();
        assert_eq!(kp.kind, PathKind::Relative);
    }

    // ---- Resolution tests (use temp directories) ----

    #[test]
    fn resolve_relative_path() {
        let dir = make_test_dir("resolve_relative");
        let data_file = dir.join("rain.csv");
        fs::write(&data_file, "test").unwrap();

        let mut kp = KalixPath::parse("rain.csv").unwrap();
        let resolved = kp.resolve(&dir).unwrap();
        assert_eq!(resolved, &data_file);
        cleanup(&dir);
    }

    #[test]
    fn resolve_absolute_path() {
        let dir = make_test_dir("resolve_absolute");
        let data_file = dir.join("rain.csv");
        fs::write(&data_file, "test").unwrap();

        let mut kp = KalixPath::parse(data_file.to_str().unwrap()).unwrap();
        let resolved = kp.resolve(&dir).unwrap();
        assert_eq!(resolved, &data_file);
        cleanup(&dir);
    }

    #[test]
    fn resolve_trailhead_found_in_ancestor() {
        let project = make_test_dir("trailhead_ancestor");

        let data_dir = project.join("data");
        fs::create_dir_all(&data_dir).unwrap();
        let data_file = data_dir.join("evap.csv");
        fs::write(&data_file, "test").unwrap();

        let context = project.join("models").join("deep").join("nested");
        fs::create_dir_all(&context).unwrap();

        let mut kp = KalixPath::parse("^/data/evap.csv").unwrap();
        let resolved = kp.resolve(&context).unwrap();
        assert_eq!(resolved, &data_file);
        cleanup(&project);
    }

    #[test]
    fn resolve_trailhead_nearest_wins() {
        let project = make_test_dir("trailhead_nearest");

        // Far copy
        let far_data = project.join("data");
        fs::create_dir_all(&far_data).unwrap();
        fs::write(far_data.join("evap.csv"), "far").unwrap();

        // Near copy
        let near_data = project.join("models").join("data");
        fs::create_dir_all(&near_data).unwrap();
        let near_file = near_data.join("evap.csv");
        fs::write(&near_file, "near").unwrap();

        let context = project.join("models").join("deep");
        fs::create_dir_all(&context).unwrap();

        let mut kp = KalixPath::parse("^/data/evap.csv").unwrap();
        let resolved = kp.resolve(&context).unwrap();
        assert_eq!(resolved, &near_file);
        cleanup(&project);
    }

    #[test]
    fn resolve_trailhead_found_in_context_dir() {
        let dir = make_test_dir("trailhead_context");
        let data_file = dir.join("evap.csv");
        fs::write(&data_file, "test").unwrap();

        let mut kp = KalixPath::parse("^/evap.csv").unwrap();
        let resolved = kp.resolve(&dir).unwrap();
        assert_eq!(resolved, &data_file);
        cleanup(&dir);
    }

    #[test]
    fn resolve_trailhead_not_found() {
        let dir = make_test_dir("trailhead_notfound");
        let mut kp = KalixPath::parse("^/nonexistent.csv").unwrap();
        let result = kp.resolve(&dir);
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("not found in any ancestor"));
        cleanup(&dir);
    }
}
