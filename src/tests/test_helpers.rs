use approx::assert_relative_eq;

/// Print a diagnostic to stderr describing how two strings expected to be byte-identical
/// differ: line-ending style mismatch, the first differing line, and overall line counts.
/// Call this before an `assert_eq!` so the failure message has actionable context to
/// go with the raw before/after dump.
pub fn print_text_diff(original: &str, actual: &str) {
    if original == actual {
        return;
    }

    let original_crlf = original.contains("\r\n");
    let actual_crlf = actual.contains("\r\n");
    if original_crlf != actual_crlf {
        eprintln!(
            "text mismatch: line-ending style differs (expected CRLF={}, actual CRLF={})",
            original_crlf, actual_crlf
        );
    }

    let original_lines: Vec<&str> = original.lines().collect();
    let actual_lines: Vec<&str> = actual.lines().collect();
    let first_diff = original_lines
        .iter()
        .zip(actual_lines.iter())
        .position(|(a, b)| a != b);

    match first_diff {
        Some(i) => {
            eprintln!("text mismatch at line {}:", i + 1);
            eprintln!("  expected: {:?}", original_lines[i]);
            eprintln!("  actual:   {:?}", actual_lines[i]);
        }
        None => {
            eprintln!("text mismatch: lines match (ignoring line-ending style)");
        }
    }
    eprintln!(
        "  (expected {} lines, actual {} lines)",
        original_lines.len(),
        actual_lines.len()
    );
}

/// Compare a (count, mean, std_dev) regression-test tuple against an expected baseline.
/// The count must match exactly; the floats are compared with relative tolerance to
/// absorb cross-platform last-bit differences (libm vs MSVCRT, FMA, vectorization).
pub fn assert_stats_close(
    new: (usize, f64, f64),
    old: (usize, f64, f64),
    key: &str,
) {
    println!("\n{}", key);
    println!("new_answer: {:?}", new);
    println!("old_answer: {:?}", old);
    assert_eq!(new.0, old.0, "{}: length mismatch", key);
    assert_relative_eq!(new.1, old.1, max_relative = 1e-15);
    assert_relative_eq!(new.2, old.2, max_relative = 1e-15);
}
