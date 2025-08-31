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

