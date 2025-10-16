use crate::data_management::constants_cache::ConstantsCache;
use crate::misc::misc_functions::is_valid_variable_name;
/*
Test basic functionality: add constants, set values, and retrieve them.
 */
#[test]
fn test_constants_cache_basic_operations() {
    println!("========== STARTING TEST ==========");
    let mut cache = ConstantsCache::new();

    // Add a constant and set its value
    let idx1 = cache.set_value("c.gravity", 9.81);
    println!("Added 'c.gravity' at idx: {}", idx1);

    // Add another constant
    let idx2 = cache.set_value("c.pi", 3.14159);
    println!("Added 'c.pi' at idx: {}", idx2);

    // Retrieve the values
    assert_eq!(cache.get_value(idx1), 9.81);
    assert_eq!(cache.get_value(idx2), 3.14159);
    assert_eq!(cache.len(), 2);

    println!("All values retrieved correctly!");
}


/*
Test that add_if_needed_and_get_idx returns the same idx for duplicate names,
and that setting a value updates the existing entry.
 */
#[test]
fn test_constants_cache_duplicate_names() {
    let mut cache = ConstantsCache::new();

    // Add a constant without assigning a value
    let idx1 = cache.add_if_needed_and_get_idx("c.temperature");
    println!("First add of 'c.temperature' at idx: {}", idx1);

    // Try to add the same constant again - should return same idx
    let idx2 = cache.add_if_needed_and_get_idx("c.temperature");
    println!("Second add of 'c.temperature' at idx: {}", idx2);
    assert_eq!(idx1, idx2);

    // Now set the value
    let idx3 = cache.set_value("c.temperature", 25.0);
    assert_eq!(idx3, idx1);
    assert_eq!(cache.get_value(idx3), 25.0);

    // Update the value
    cache.set_value("c.temperature", 30.0);
    assert_eq!(cache.get_value(idx1), 30.0);

    // Should still only have 1 constant
    assert_eq!(cache.len(), 1);
}


/*
Test that assert_all_constants_have_assigned_values correctly identifies
unassigned constants and returns an error.
 */
#[test]
fn test_constants_cache_assert_all_assigned() {
    let mut cache = ConstantsCache::new();

    // Add a constant with a value
    cache.set_value("c.assigned_constant", 42.0);

    // Add a constant without assigning a value
    cache.add_if_needed_and_get_idx("c.unassigned_constant");

    // Should fail because one constant is unassigned
    let result = cache.assert_all_constants_have_assigned_values();
    println!("Assertion result: {:?}", result);
    assert!(result.is_err());
    assert!(result.unwrap_err().contains("c.unassigned_constant"));

    // Now assign the missing constant
    cache.set_value("c.unassigned_constant", 100.0);

    // Should succeed now
    let result = cache.assert_all_constants_have_assigned_values();
    assert!(result.is_ok());
    println!("All constants have assigned values!");
}



#[test]
fn test_variable_name_checker() {
    assert!(is_valid_variable_name("c.temperature"));
    assert!(is_valid_variable_name("c.unassigned_temperature"));
    assert!(is_valid_variable_name("c.big_dam.volume"));
    assert!(is_valid_variable_name("node.big_dam.ds_1"));
    assert!(!is_valid_variable_name("c.Temperature")); //has uppercase char
    assert!(!is_valid_variable_name("123_abc")); //starts with digit
    assert!(!is_valid_variable_name("abc+def")); //contains +
    assert!(!is_valid_variable_name("abc(d)ef")); //contains brackets
    assert!(!is_valid_variable_name("abc.def.")); //ends with a .
}
