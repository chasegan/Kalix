use crate::numerical::interpolation::lerp;

#[test]
fn test_interpolation_midpoint() {
    let xs = vec![0.0, 10.0];
    let ys = vec![0.0, 100.0];
    assert_eq!(lerp(&xs, &ys, 5.0), 50.0);
}

#[test]
fn test_interpolation_on_points() {
    let xs = vec![0.0, 10.0, 20.0];
    let ys = vec![0.0, 100.0, 300.0];
    assert_eq!(lerp(&xs, &ys, 0.0), 0.0);
    assert_eq!(lerp(&xs, &ys, 10.0), 100.0);
    assert_eq!(lerp(&xs, &ys, 20.0), 300.0);
}

#[test]
fn test_interpolation_between_segments() {
    let xs = vec![0.0, 10.0, 20.0];
    let ys = vec![0.0, 100.0, 300.0];
    assert_eq!(lerp(&xs, &ys, 5.0), 50.0);   // first segment
    assert_eq!(lerp(&xs, &ys, 15.0), 200.0); // second segment
}

#[test]
fn test_extrapolation_below() {
    let xs = vec![10.0, 20.0];
    let ys = vec![100.0, 200.0];
    assert_eq!(lerp(&xs, &ys, 0.0), 0.0);   // extrapolate below
    assert_eq!(lerp(&xs, &ys, -10.0), -100.0);
}

#[test]
fn test_extrapolation_above() {
    let xs = vec![10.0, 20.0];
    let ys = vec![100.0, 200.0];
    assert_eq!(lerp(&xs, &ys, 30.0), 300.0);  // extrapolate above
    assert_eq!(lerp(&xs, &ys, 40.0), 400.0);
}

#[test]
fn test_single_point() {
    let xs = vec![5.0];
    let ys = vec![50.0];
    assert_eq!(lerp(&xs, &ys, 0.0), 50.0);
    assert_eq!(lerp(&xs, &ys, 100.0), 50.0);
}
