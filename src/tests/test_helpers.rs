use approx::assert_relative_eq;

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
