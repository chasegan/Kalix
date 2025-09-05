#[allow(dead_code)]
pub fn quadratic_plus(a: f64, b: f64, c: f64) -> f64 {
    let d = b * b - 4.0 * a * c;
    if d < 0f64 {
        f64::NAN
    } else if a == 0f64 {
        -c / b
    } else {
        (-b + d.sqrt()) / (2.0 * a)
    }
}

#[allow(dead_code)]
pub fn quadratic_minus(a: f64, b: f64, c: f64) -> f64 {
    let d = b * b - 4.0 * a * c;
    if d < 0f64 {
        f64::NAN
    } else if a == 0f64 {
        -c / b
    } else {
        (-b - d.sqrt()) / (2.0 * a)
    }
}


/// Lets you subtract two u64 values and get a signed result. This function returns the value of
/// (a - b) as an i64. The result is a signed integer therefore it can still be correct even if b>a.
pub fn u64_subtraction(a: u64, b: u64) -> i64 {
    if a > b {
        let diff = a - b;
        if diff > i64::MAX as u64 {
            panic!("Cannot fit u64 subtraction ({} - {}) into i64.", a, b)
        }
        diff as i64
    } else {
        let diff = b - a;
        if diff > i64::MAX as u64 {
            panic!("Cannot fit u64 subtraction ({} - {}) into i64.", a, b)
        }
        -(diff as i64)
    }
}

