use std::fs;
use std::path::Path;

fn main() {
    // Read version from VERSION file
    let version_file = Path::new("VERSION");
    let version = fs::read_to_string(version_file)
        .expect("Failed to read VERSION file")
        .trim()
        .to_string();

    // Set KALIX_VERSION environment variable for compile-time access
    println!("cargo:rustc-env=KALIX_VERSION={}", version);

    // Re-run build script if VERSION file changes
    println!("cargo:rerun-if-changed=VERSION");

    // Read Cargo.toml version for validation
    let cargo_toml = fs::read_to_string("Cargo.toml").expect("Failed to read Cargo.toml");
    let cargo_version = cargo_toml
        .lines()
        .find(|line| line.starts_with("version = "))
        .and_then(|line| line.split('"').nth(1))
        .expect("Failed to find version in Cargo.toml");

    // Warn if versions don't match
    if cargo_version != version {
        println!(
            "cargo:warning=VERSION file ({}) does not match Cargo.toml version ({}). Run: python update_version.py",
            version, cargo_version
        );
    }
}
