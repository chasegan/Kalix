use crate::numerical::table_discontinuous::TableDiscontinuous;

/// Build a discontinuous table from points [0,0] [3,1] [3,2] [5,2] [7,4].
/// This produces 3 segments:
///   seg 0: x in [0,3], y = x/3        (m=1/3, c=0)
///   seg 1: x in [3,5], y = 2          (m=0,   c=2)  discontinuous jump at x=3
///   seg 2: x in [5,7], y = x - 3      (m=1,   c=-3)
#[test]
fn test_discontinuous_interpolation() {
    let mut tab = TableDiscontinuous::new();
    tab.add_point(0.0, 0.0);
    tab.add_point(3.0, 1.0);
    tab.add_point(3.0, 2.0); // discontinuity: new segment starts at y=2
    tab.add_point(5.0, 2.0);
    tab.add_point(7.0, 4.0);

    assert_eq!(tab.nsegs(), 3);

    // Extrapolation below range (uses seg 0)
    assert!((tab.interpolate_or_extrapolate(-1.0) - (-1.0 / 3.0)).abs() < 1e-10);

    // On x_min (seg 0)
    assert_eq!(tab.interpolate_or_extrapolate(0.0), 0.0);

    // Midway in seg 0
    assert_eq!(tab.interpolate_or_extrapolate(1.5), 0.5);

    // Junction at x=3: convention xlo < xvalue <= xhi assigns to seg 0
    assert_eq!(tab.interpolate_or_extrapolate(3.0), 1.0);

    // Midway in seg 1
    assert_eq!(tab.interpolate_or_extrapolate(4.0), 2.0);

    // Junction at x=5: assigned to seg 1
    assert_eq!(tab.interpolate_or_extrapolate(5.0), 2.0);

    // Midway in seg 2
    assert_eq!(tab.interpolate_or_extrapolate(6.0), 3.0);

    // On x_max (seg 2)
    assert_eq!(tab.interpolate_or_extrapolate(7.0), 4.0);

    // Extrapolation above range (uses seg 2)
    assert_eq!(tab.interpolate_or_extrapolate(8.0), 5.0);
}
