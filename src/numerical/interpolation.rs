/// Linear interpolation (or extrapolation) given a set of x,y points.
/// Assumes x_points are sorted in ascending order.
/// If x is outside the range, linearly extrapolates from the nearest two points.
pub fn lerp(x_points: &[f64], y_points: &[f64], x: f64) -> f64 {
    debug_assert!(!x_points.is_empty(), "x_points must not be empty");
    debug_assert_eq!(x_points.len(), y_points.len(), "x_points and y_points must have same length");

    let n = x_points.len();

    // Single point - return that y value
    if n == 1 {
        return y_points[0];
    }

    // Find the segment for interpolation/extrapolation
    let (i0, i1) = if x <= x_points[0] {
        // Extrapolate below range
        (0, 1)
    } else if x >= x_points[n - 1] {
        // Extrapolate above range
        (n - 2, n - 1)
    } else {
        // Find segment by linear search (could use binary search for large arrays)
        let mut i = 0;
        while i < n - 1 && x_points[i + 1] < x {
            i += 1;
        }
        (i, i + 1)
    };

    let x0 = x_points[i0];
    let x1 = x_points[i1];
    let y0 = y_points[i0];
    let y1 = y_points[i1];

    // Guard against zero-width segment
    if x0 == x1 {
        return y0;
    }

    // Linear interpolation/extrapolation
    y0 + (x - x0) * (y1 - y0) / (x1 - x0)
}
