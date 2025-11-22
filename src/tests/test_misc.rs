use crate::numerical::mathfn::u64_subtraction;
use crate::misc::misc_functions::format_f64;

#[test]
fn test_u64_subtraction() {
    let a = u64::MAX - 1000;
    let b = u64::MAX - 2000;
    let c = u64_subtraction(a, b);
    println!("({a} - {b}) = {c}");
    assert_eq!(c, 1000);

    let a = u64::MAX - 2000;
    let b = u64::MAX - 1000;
    let c = u64_subtraction(a, b);
    println!("({a} - {b}) = {c}");
    assert_eq!(c, -1000);

    let a = u64::MIN + 1000;
    let b = u64::MIN + 2000;
    let c = u64_subtraction(a, b);
    println!("({a} - {b}) = {c}");
    assert_eq!(c, -1000);

    let a = u64::MIN + 2000;
    let b = u64::MIN + 1000;
    let c = u64_subtraction(a, b);
    println!("({a} - {b}) = {c}");
    assert_eq!(c, 1000);
}

#[test]
fn test_format_f64() {
    // Test integers
    assert_eq!(format_f64(1.0), "1");
    assert_eq!(format_f64(2.0), "2");
    assert_eq!(format_f64(10.0), "10");
    assert_eq!(format_f64(100.0), "100");

    // Test normal notation (no trailing zeros)
    assert_eq!(format_f64(0.5), "0.5");
    assert_eq!(format_f64(0.25), "0.25");
    assert_eq!(format_f64(0.123), "0.123");
    assert_eq!(format_f64(0.1234567890), "0.123456789");
    assert_eq!(format_f64(12.3400000), "12.34");
    assert_eq!(format_f64(123.0), "123");

    // Test scientific notation for small values
    assert_eq!(format_f64(0.00001), "1e-5");
    assert_eq!(format_f64(0.0000123), "1.23e-5");
    assert_eq!(format_f64(0.00000001), "1e-8");
    assert_eq!(format_f64(5e-10), "5e-10");

    // Test scientific notation for large values
    assert_eq!(format_f64(1000000.0), "1e+6");
    assert_eq!(format_f64(1234567.0), "1.234567e+6");
    assert_eq!(format_f64(1e10), "1e+10");

    // Test boundary values
    assert_eq!(format_f64(0.0001), "0.0001");   // Just at boundary - normal notation
    assert_eq!(format_f64(0.00009999), "9.999e-5"); // Just below boundary - scientific
    assert_eq!(format_f64(999999.0), "999999");  // Just below 1e6 - normal notation
    assert_eq!(format_f64(1000001.0), "1.000001e+6"); // Just above 1e6 - scientific

    // Test negative values
    assert_eq!(format_f64(-0.5), "-0.5");
    assert_eq!(format_f64(-1.0), "-1");
    assert_eq!(format_f64(-0.00001), "-1e-5");
    assert_eq!(format_f64(-1000000.0), "-1e+6");

    // Test zero
    assert_eq!(format_f64(0.0), "0");

    // Test values with many decimal places
    assert_eq!(format_f64(0.3333333333), "0.3333333333");
    assert_eq!(format_f64(0.6666666667), "0.6666666667");

    // Test that large numbers preserve precision in scientific notation
    // Since we're no longer doing noise cleanup, these will format normally
    assert_eq!(format_f64(3.9999999999999e15), "3.9999999999999e+15");
    assert_eq!(format_f64(1.0000000000001e12), "1.0000000000001e+12");

    // Test clean numbers
    assert_eq!(format_f64(3.4), "3.4");
    assert_eq!(format_f64(10.25), "10.25");
}

