use crate::tid::utils::{date_string_to_u64, u64_to_date_string};

#[test]
fn test_date_string_to_u64() {
    let ans_u64 = date_string_to_u64("1944-2-14").unwrap();
    let ans_str = u64_to_date_string(ans_u64);
    assert_eq!(ans_u64, 9223372036038036608);
    assert_eq!(ans_str, "1944-02-14");
    //println!("Answer = {ans_u64} = {ans_str}");
}