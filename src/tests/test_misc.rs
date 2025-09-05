use crate::numerical::mathfn::u64_subtraction;

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

